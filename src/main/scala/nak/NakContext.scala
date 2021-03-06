package nak

/**
 Copyright 2013 Jason Baldridge
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at 
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License. 
*/

import nak.core._
import nak.data._
import nak.liblinear.{Model => LiblinearModel, LiblinearConfig, LiblinearTrainer}
import nak.liblinear.LiblinearUtil._

import scala.collection.JavaConversions._
import scala.io.Source
import java.io.{File,BufferedReader,FileReader}

/**
 * An object that provides common functions needed for using Nak.
 *
 * @author jasonbaldridge
 */
object NakContext {

  /**
   * Convert examples stored in CSV format (one per line) into a sequence of Examples.
   */
  def fromCsvFile(filename: String): Iterator[Example[String,Seq[FeatureObservation[String]]]] = {
    for (line <- Source.fromFile(filename).getLines) yield {
      val items = line.split(",")
      val features = items.dropRight(1).map(descr=>FeatureObservation(descr))
      val label = items.last
      Example(label, features)
    }
  }

  /**
   * Convert examples that are stored as files in directories, where each directory name acts
   * as the label for all the files it contains. (E.g. the 20 News Groups data.)
   */ 
  def fromLabeledDirs(topdir: File)(implicit codec: scala.io.Codec): Iterator[Example[String,String]] = {
    for (dir <- topdir.listFiles.toIterator.filter(_.isDirectory);
         label = dir.getName;
         file <- dir.listFiles.toIterator) yield {
      val fileSource = Source.fromFile(file);
      val text = fileSource.mkString;
      fileSource.close
      Example(label, text, file.getName)
    }
  }


  /**
   * Trains a classifier given examples and featurizer. Handles indexation of features,
   * and creates a classifier that can be applied directly to new raw observations.
   *
   * This is the easiest way to build and use a classifier.
   */
  def trainClassifier[I](
    config: LiblinearConfig, 
    featurizer: Featurizer[I,String], 
    rawExamples: Seq[Example[String, I]]
  ): IndexedClassifier[String] with FeaturizedClassifier[String, I] = {

    // Featurize and index the examples.
    val indexer = new ExampleIndexer    
    val examples = rawExamples.map(_.map(featurizer)).map(indexer)
    val (lmap,fmap) = indexer.getMaps
        
    // Train the model, and then return the classifier.
    val model = trainModel(config, examples, fmap.size)
    Classifier(model, lmap, fmap, featurizer)
  }

  /**
   * Trains a classifier given indexed examples and the label and feature maps produced
   * by indexation.
   */ 
  def trainClassifier(
    config: LiblinearConfig,
    examples: TraversableOnce[Example[Int,Seq[FeatureObservation[Int]]]],
    lmap: Map[String, Int], 
    fmap: Map[String, Int]
  ): IndexedClassifier[String] = 
    Classifier(trainModel(config,examples,fmap.size), lmap, fmap)

  /**
   * Save a classifier to disk by using Java serialization.
   */ 
  def saveClassifier(classifier: Classifier, filename: String) {
    import java.io._
    val stream = new FileOutputStream(filename)
    new ObjectOutputStream(stream).writeObject(classifier)
    stream.close
  }

  /**
   * Read a classifier from disk by using Java deserialization.
   */ 
  def loadClassifier[C<:Classifier](filename: String) = {
    import java.io._
    val stream = new FileInputStream(filename)
    val classifier = new ObjectInputStream(stream).readObject.asInstanceOf[C]
    stream.close
    classifier
  }

  /**
   * Train a Liblinear classifier using examples that have been created from a
   * legacy DataIndexer.
   */
  @deprecated(message="DataIndexers are being phased out.", since="1.1.2")
  def trainClassifier(config: LiblinearConfig, indexer: nak.data.DataIndexer) = {

    val labels = indexer.getOutcomeLabels
    
    // We unfortunately need to fix up the contexts so that feature indices start at 1.
    val zeroBasedFeatures = indexer.getPredLabels
    val zeroReindex = zeroBasedFeatures.length
    val features = zeroBasedFeatures ++ Array(zeroBasedFeatures(0))
    features(0) = "DUMMY FEATURE"
    
    val contexts = indexer.getContexts.map { context => {
      context.map { _ match {
        case 0 => zeroReindex
        case x => x
      }}
    }}

    // Use values of 1.0 if the features were binary.
    val values = 
      if (indexer.getValues != null) indexer.getValues
      else contexts.map(_.map(x=>1.0f))

    // Get the responses and observations ready for Liblinear
    val responses = indexer.getOutcomeList.map(_.toDouble)
    val observationsAsTuples = contexts.zip(values).map{ 
      case (c,v) => 
        c.zip(v).groupBy(_._1).mapValues(_.map(_._2)).mapValues(_.sum.toFloat).toArray
    }

    val observations = createLiblinearMatrix(observationsAsTuples)

    // Train the model, and then return the classifier.
    val model = new LiblinearTrainer(config)(responses, observations, features.length)
    Classifier.createLegacy(model, labels, features)
  }

  /**
   * Trains a liblinear model given indexed examples. Note: a model is basically just the
   * parameters. The classifiers returned by trainClassifier methods wrap the parameters
   * into a number of convenience methods that makes it easier to use the model.
   */
  def trainModel(
    config: LiblinearConfig,
    examples: TraversableOnce[Example[Int,Seq[FeatureObservation[Int]]]],
    numFeatures: Int): LiblinearModel = {

    val (responses, observationsAsTuples) = 
      examples.map(ex => (ex.label, ex.features.map(_.tuple).toSeq)).toSeq.unzip
    val observations = createLiblinearMatrix(observationsAsTuples)
    new LiblinearTrainer(config)(responses.map(_.toDouble).toArray, observations, numFeatures)
  }


  /**
   * Given a sequence of feature observations (a feature and its magnitude), combine
   * multiple instances of the same feature, and then sort the result.
   *
   * E.g. Seq[("foo",1.0),("bar",1.0),("foo",2.0)]
   *  becomes
   *      Seq[("bar",1.0),("foo",3.0)]
   */
  def condense(features: Seq[FeatureObservation[Int]]) =
    features
      .groupBy(_.feature)
      .values
      .map(_.reduce(_+_))
      .toSeq
      .sortBy(_.feature)


  /**
   * Given the labels and scores that have been produced for each, return the label
   * with the highest score.
   */
  def maxLabel(labels: Seq[String])(scores: Seq[Double]) =
    labels.zip(scores).maxBy(_._2)._1


}
