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

import _root_.io.prediction.controller.{ EmptyActualResult, EmptyEvaluationInfo, PDataSource, Params }
import _root_.io.prediction.data.storage.PropertyMap
import _root_.io.prediction.data.store.PEventStore
import grizzled.slf4j.Logger
import io.prediction.core.{ EventWindow, SelfCleaningDataSource }
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.template.conversions.{ ActionID, ItemID }

/** Taken from engine.json these are passed in to the DataSource constructor
 *
 *  @param appName registered name for the app
 *  @param eventNames a list of named events expected. The first is the primary event, the rest are secondary. These
 *                   will be used to create the primary correlator and cross-cooccurrence secondary correlators.
 */
case class DataSourceParams(
  appName: String,
  eventNames: List[String], // IMPORTANT: eventNames must be exactly the same as URAlgorithmParams eventNames
  eventWindow: Option[EventWindow]) extends Params

/** Read specified events from the PEventStore and creates RDDs for each event. A list of pairs (eventName, eventRDD)
 *  are sent to the Preparator for further processing.
 *  @param dsp parameters taken from engine.json
 */
class DataSource(val dsp: DataSourceParams)
  extends PDataSource[TrainingData, EmptyEvaluationInfo, Query, EmptyActualResult]
  with SelfCleaningDataSource {

  @transient override lazy val logger: Logger = Logger[this.type]

  override def appName: String = dsp.appName
  override def eventWindow: Option[EventWindow] = dsp.eventWindow

  /** Reads events from PEventStore and create and RDD for each */
  override def readTraining(sc: SparkContext): TrainingData = {

    val eventNames = dsp.eventNames
    cleanPersistedPEvents(sc)
    val eventsRDD = PEventStore.find(
      appName = dsp.appName,
      entityType = Some("user"),
      eventNames = Some(eventNames),
      targetEntityType = Some(Some("item")))(sc).repartition(sc.defaultParallelism)

    // now separate the events by event name
    val actionRDDs = eventNames.map { eventName =>
      val actionRDD = eventsRDD.filter { event =>
        require(eventNames.contains(event.event), s"Unexpected event $event is read.") // is this really needed?
        require(event.entityId.nonEmpty && event.targetEntityId.get.nonEmpty, "Empty user or item ID")
        eventName.equals(event.event)
      }.map { event =>
        (event.entityId, event.targetEntityId.get)
      }.cache()

      logger.debug(s"Action[$eventName] -> ${actionRDD.count()}")
      (eventName, actionRDD)
    } filterNot { case (_, actionRDD) => actionRDD.isEmpty() }

    // aggregating all $set/$unsets for metadata fields, which are attached to items
    val fieldsRDD: RDD[(ItemID, PropertyMap)] = PEventStore.aggregateProperties(
      appName = dsp.appName,
      entityType = "item")(sc)

    // Have a list of (actionName, RDD), for each action
    // todo: some day allow data to be content, which requires rethinking how to use EventStore
    TrainingData(actionRDDs, fieldsRDD)
  }
}

/** Low level RDD based representation of the data ready for the Preparator
 *
 *  @param actions List of Tuples (actionName, actionRDD)qw
 *  @param fieldsRDD RDD of item keyed PropertyMap for item metadata
 */
case class TrainingData(
  actions: Seq[(ActionID, RDD[(String, String)])],
  fieldsRDD: RDD[(ItemID, PropertyMap)]) extends Serializable {

  override def toString: String = {
    val a = actions.map { t =>
      s"${t._1} actions: [count:${t._2.count()}] + sample:${t._2.take(2).toList} "
    }.toString()
    val f = s"Item metadata: [count:${fieldsRDD.count}] + sample:${fieldsRDD.take(2).toList} "
    a + f
  }

}