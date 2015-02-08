package etch

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import scala.concurrent.Future

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.kms.model.KeyUnavailableException
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.IOUtils
import scala.concurrent.ExecutionContext.Implicits.global

class CloseableLoan[C <: Closeable](closeable: C) {
  def map[A](f: (C) => A) = {
    try {
      f(closeable)
    }
    finally {
      closeable.close()
    }
  }
}

object CloseableLoan {
  def borrow[C <: Closeable, A](closeable: C)(f: (C) => A) = {
    new CloseableLoan(closeable).map(f)
  }
}

import etch.CloseableLoan.borrow

object S3Connector {

  // TODO - eventually move this out of code and re-gen the keys so it's not in version control
  private val awsKey = "AKIAIABEMN4LI6457JOA"
  private val awsSecret = "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"

  private val credentials = new BasicAWSCredentials(awsKey, awsSecret)
  private val client = new AmazonS3Client(credentials)

  private val bucket = "kurtome-etch-image"

  def get(key: String): Option[Array[Byte]] = {
    try {
      borrow(client.getObject(bucket, key)) { s3Obj =>
        Some(IOUtils.toByteArray(s3Obj.getObjectContent))
      }
    }
    catch {
      case e: KeyUnavailableException => None
    }
  }

  def put(key: String, content: Array[Byte]): Unit = {
    borrow(new ByteArrayInputStream(content)) { contentStream =>
      val metadata = new ObjectMetadata()
      metadata.setContentLength(content.length)
      metadata.setContentMD5(DigestUtils.md5Hex(content))

      client.putObject(bucket, key, contentStream, metadata)
    }
  }
}

/**
 * This provides an async layer over the blocking HTTP requests to S3
 * in order to separate the slow I/O from blocking the app request handlers
 */
object AsyncS3Connector {
  import S3Connector.{get => syncGet, put => syncPut}

  def get(key: String) = Future {
    syncGet(key)
  }

  def put(key: String, content: Array[Byte]) = Future {
    syncPut(key, content)
  }
}
