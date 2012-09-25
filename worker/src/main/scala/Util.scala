/**
 * Utils.scala
 * 
 * Logging singleton, ActorSystem singleton, progress reporting classes
 * 
 * Overview Project, created August 2012
 * @author Jonathan Stray
 * 
 */

package overview.util

import akka.actor._
import org.slf4j.LoggerFactory

// Worker logging singleton object. Pass-through to LogBack
object Logger {
   private def logger = LoggerFactory.getLogger("WORKER")

   def trace(msg:String) = logger.trace(msg)
   def debug(msg:String) = logger.debug(msg)
   def info(msg:String) = logger.info(msg)
   def warn(msg:String) = logger.warn(msg)
   def error(msg:String) = logger.error(msg)   
}

// Singleton Akka actor system object. One per process, managing all actors.
object WorkerActorSystem {
  def withActorSystem(f: ActorSystem => Unit) {
    val context = ActorSystem("WorkerActorSystem")
    f(context)
    context.shutdown
  }
}


sealed abstract class DocumentSetCreationJobStateDescription(key: String, arg: String = "") {
  override def toString = if (!arg.isEmpty) key + ":" + arg
			  else key
}

object DocumentSetCreationJobStateDescription {
  private type Description = DocumentSetCreationJobStateDescription

  case class NoDescription() extends Description("")
  case class OutOfMemory() extends Description("out_of_memory")
  case class WorkerError() extends Description("worker_error")
  case class Retrieving() extends Description("retrieving_documents")
  case class Clustering() extends Description("clustering")
  case class Saving() extends Description("saving_document_tree")
  case class Done() extends Description("job_complete")
  case class ClusteringLevel(n: Int)  extends Description("clustering_level", n.toString)
}

object Progress {
  import DocumentSetCreationJobStateDescription._
  // Little class that represents progress
  case class Progress(fraction:Double, status:DocumentSetCreationJobStateDescription, hasError:Boolean = false) 

  // Callback function to inform of progress, and returns false if operation should abort
  type ProgressAbortFn = (Progress) => Boolean 
  
  // Turns a sub-task progress into overall task progress
  def makeNestedProgress(inner:ProgressAbortFn, startFraction:Double, endFraction:Double) : ProgressAbortFn = {
    (progress) => inner(Progress(startFraction + (endFraction - startFraction) * progress.fraction, progress.status, progress.hasError))
  }
  
  // stub that you can pass in when you don't case about progress reporting
  def NoProgressReporting(p:Progress):Boolean = { false } 
}

