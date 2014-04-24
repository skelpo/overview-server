package org.overviewproject.jobhandler.filegroup

import akka.actor.Props

class TestFileGroupJobQueue(tasks: Seq[Long]) extends FileGroupJobQueue {

  class TestStorage extends Storage {
    override def uploadedFileIds(fileGroupId: Long): Set[Long] = tasks.toSet
  }

  override protected val storage = new TestStorage
}

object TestFileGroupJobQueue {
  def apply(tasks: Seq[Long]): Props = Props(new TestFileGroupJobQueue(tasks)) 
}

