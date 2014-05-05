package org.overviewproject.jobhandler.filegroup

import akka.actor.Actor

object ProgressReporterProtocol {
  case class StartJob(jobId: Long, numberOfTasks: Int)
  case class StartTask(jobId: Long, taskId: Long)
  case class CompleteTask(jobId: Long, taskId: Long)
}

case class JobProgress(numberOfTasks: Int, tasksStarted: Int = 0, fraction: Double = 0.0) {
  def startTask: JobProgress = this.copy(tasksStarted = tasksStarted + 1)
  def completeTask: JobProgress = this.copy(fraction = tasksStarted.toDouble / numberOfTasks)
}

trait ProgressReporter extends Actor {
  import ProgressReporterProtocol._

  private var jobProgress: Map[Long, JobProgress] = Map.empty

  protected val storage: Storage

  protected trait Storage {
    def updateProgress(jobId: Long, fraction: Double, description: String): Unit
  }

  def receive = {
    case StartJob(jobId, numberOfTasks) => updateProgress(jobId, JobProgress(numberOfTasks))
    case StartTask(jobId, taskId) => updateTaskForJob(jobId, _.startTask)
    case CompleteTask(jobId, taskId) => updateTaskForJob(jobId, _.completeTask)
  }

  private def description(progress: JobProgress): String =
    s"processing_files:${progress.tasksStarted}:${progress.numberOfTasks}"

  private def updateTaskForJob(jobId: Long, updateFunction: JobProgress => JobProgress): Unit =
    jobProgress.get(jobId).map { p => updateProgress(jobId, updateFunction(p)) } 

  private def updateProgress(jobId: Long, progress: JobProgress): Unit = {
    jobProgress += (jobId -> progress)
    storage.updateProgress(jobId, progress.fraction, description(progress))
  }

}