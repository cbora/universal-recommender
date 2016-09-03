/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.template

import grizzled.slf4j.Logger
import io.prediction.data.storage.DataMap
import org.apache.mahout.math.indexeddataset.IndexedDataset
import org.apache.mahout.sparkbindings.indexeddataset.IndexedDatasetSpark
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime
import org.json4s.JsonAST.JArray
import org.json4s._
import org.template.conversions.{ IndexedDatasetConversions, ItemID }

/** Universal Recommender models to save in ES */
class URModel(
  coocurrenceMatrices: Seq[(ItemID, IndexedDataset)] = Seq.empty,
  fieldsRDD: RDD[(String, DataMap)],
  propertiesRDD: RDD[Map[String, AnyRef]],
  typeMappings: Map[String, String] = Map.empty, // maps fieldname that need type mapping in Elasticsearch
  nullModel: Boolean = false) {

  @transient lazy val logger: Logger = Logger[this.type]

  /** Save all fields to be indexed by Elasticsearch and queried for recs
   *  This will is something like a table with row IDs = item IDs and separate fields for all
   *  cooccurrence and cross-cooccurrence correlators and metadata for each item. Metadata fields are
   *  limited to text term collections so vector types. Scalar values can be used but depend on
   *  Elasticsearch's support. One exception is the Data scalar, which is also supported
   *  @return always returns true since most other reasons to not save cause exceptions
   */
  def save(dateNames: Seq[String], esIndex: String, esType: String): Boolean = {

    if (nullModel) throw new IllegalStateException("Saving a null model created from loading an old one.")

    // for ES we need to create the entire index in an rdd of maps, one per item so we'll use
    // convert cooccurrence matrices into correlators as RDD[(itemID, (actionName, Seq[itemID])]
    // do they need to be in Elasticsearch format
    logger.info("Converting cooccurrence matrices into correlators")
    val correlators: Seq[RDD[(ItemID, Map[String, Any])]] = coocurrenceMatrices.map {
      case (actionName, dataset) =>
        dataset.asInstanceOf[IndexedDatasetSpark].toStringMapRDD(actionName).asInstanceOf[RDD[(ItemID, Map[String, Any])]]
    }

    logger.info(s"Ready to pass date fields names to closure $dateNames")
    // convert the PropertyMap into Map[String, Seq[String]] for ES
    logger.info("Converting PropertyMap into Elasticsearch style rdd")

    val properties: RDD[(ItemID, Map[String, Any])] = fieldsRDD.map {
      case (item, dataMap) => (item, dataMap.fields.collect {
        case (fieldName, jvalue) =>
          jvalue match {
            case JArray(list) => fieldName -> list.collect { case JString(s) => s }
            case JString(s) => // name for this field is in engine params
              if (dateNames.contains(fieldName)) {
                // one of the date fields
                val date: java.util.Date = new DateTime(s).toDate
                fieldName -> date
              } else if (BackfillFieldName.toSeq.contains(fieldName)) {
                fieldName -> s.toDouble
              } else {
                fieldName -> s
              }
            case JDouble(rank) => // only the ranking double from PopModel should be here
              fieldName -> rank
            case JInt(someInt) => // not sure what this is but pass it on
              fieldName -> someInt
            case _ => fieldName -> ""
          }
      } /*filter { case (_, value) => value.isInstanceOf[String] && value.asInstanceOf[String].nonEmpty }*/ )
    }

    // getting action names since they will be ES fields
    logger.info(s"Getting a list of action name strings")
    val allActions = coocurrenceMatrices.map { case (actionName, dataset) => actionName }

    val allPropKeys: List[String] = properties
      .flatMap { case (item, fieldMap) => fieldMap.keySet }
      .distinct.collect().toList

    // these need to be indexed with "not_analyzed" and no norms so have to
    // collect all field names before ES index create
    val allFields = (allActions ++ allPropKeys).distinct.toList // shouldn't need distinct but it's fast

    if (propertiesRDD.isEmpty) {
      // Elasticsearch takes a Map with all fields, not a tuple
      logger.info("Grouping all correlators into doc + fields for writing to index")
      logger.info(s"Finding non-empty RDDs from a list of ${correlators.length} correlators and " +
        s"${properties.isEmpty()} properties")
      val esRDDs: Seq[RDD[(String, Map[String, Any])]] =
        //(correlators ::: properties).filterNot(c => c.isEmpty())// for some reason way too slow
        correlators :+ properties
      //c.take(1).length == 0
      if (esRDDs.nonEmpty) {
        val esFields = groupAll(esRDDs).map {
          case (item, map) =>
            // todo: every map's items must be checked for value type and converted before writing to ES
            val esMap = map + ("id" -> item)
            esMap
        }
        // create a new index then hot-swap the new index by re-aliasing to it then delete old index
        logger.info("New data to index, performing a hot swap of the index.")
        EsClient.hotSwap(
          esIndex,
          esType,
          esFields.asInstanceOf[RDD[Map[String, AnyRef]]],
          allFields,
          typeMappings)
      } else {
        logger.warn("No data to write. May have been caused by a failed or stopped `pio train`, try running it again")
      }

    } else {
      // this happens when updating only the popularity backfill model
      // but to do a hotSwap we need to dup the entire index

      // create a new index then hot-swap the new index by re-aliasing to it then delete old index
      EsClient.hotSwap(esIndex, esType, propertiesRDD, allFields, typeMappings)
    }
    true
  }

  def groupAll(fields: Seq[RDD[(ItemID, (Map[String, Any]))]]): RDD[(ItemID, (Map[String, Any]))] = {
    //if (fields.size > 1 && !fields.head.isEmpty() && !fields(1).isEmpty()) {
    if (fields.size > 1) {
      fields.head.cogroup[Map[String, Any]](groupAll(fields.drop(1))).map {
        case (key, pairMapSeqs) =>
          // to be safe merge all maps but should only be one per rdd element
          val rdd1Maps = pairMapSeqs._1.foldLeft(Map.empty[String, Any])(_ ++ _)
          val rdd2Maps = pairMapSeqs._2.foldLeft(Map.empty[String, Any])(_ ++ _)
          val fullMap = rdd1Maps ++ rdd2Maps
          (key, fullMap)
      }
    } else {
      fields.head
    }
  }

}

object URModel {
  @transient lazy val logger: Logger = Logger[this.type]

  /** This is actually only used to read saved values and since they are in Elasticsearch we don't need to read
   *  this means we create a null model since it will not be used.
   *  todo: we should rejigger the template framework so this is not required.
   *  @param id ignored
   *  @param params ignored
   *  @param sc ignored
   *  @return dummy null model
   */
  def apply(id: String, params: URAlgorithmParams, sc: Option[SparkContext]): URModel = {
    // todo: need changes in PIO to remove the need for this
    val urm = new URModel(null, null, null, null, nullModel = true)
    logger.info("Created dummy null model")
    urm
  }

}
