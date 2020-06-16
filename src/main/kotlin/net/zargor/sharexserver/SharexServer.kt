package net.zargor.sharexserver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.google.gson.Gson
import com.j256.simplemagic.ContentInfo
import com.j256.simplemagic.ContentInfoUtil
import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import com.mongodb.client.MongoCollection
import com.mongodb.client.result.DeleteResult
import org.apache.commons.fileupload.FileItemIterator
import org.apache.commons.fileupload.FileUploadBase
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.commons.lang3.RandomStringUtils
import org.bson.BsonBinary
import org.bson.Document
import org.bson.json.JsonWriterSettings
import org.slf4j.LoggerFactory
import spark.Spark
import java.io.ByteArrayOutputStream
import java.util.*
import javax.activation.MimeType
import kotlin.math.ceil
import kotlin.math.roundToInt

val logger = LoggerFactory.getLogger("ShareXServer")
val gson = Gson()
val json_settings = JsonWriterSettings.builder()
        .int64Converter { value, writer -> writer.writeNumber(value.toString()) }
        .build()
const val ID_LENGTH : Int = 5

fun main(args : Array<String>) {
    (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger).level = Level.ERROR
    (logger as ch.qos.logback.classic.Logger).level = Level.INFO
    (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("org.mongodb.driver").level = Level.ERROR

    generateConfig()
    val credential = MongoCredential.createCredential(configObj.mongoUser, configObj.mongoAuthDB, configObj.mongoPassword.toCharArray())
    val mongoClient = MongoClient(ServerAddress(configObj.mongoHost, configObj.mongoPort), credential, MongoClientOptions.builder().build())
    val mongoDatabase = mongoClient.getDatabase(configObj.mongoDatabase)
    val headerColl = mongoDatabase.getCollection(configObj.mongoHeaderColl)
    val chunkColl = mongoDatabase.getCollection(configObj.mongoChunkColl)

    Spark.port(configObj.port)
    Spark.ipAddress(configObj.host)

    Spark.initExceptionHandler { e ->
        logger.error(e.message)
    }

    Spark.internalServerError { _, res ->
        res.status(500)
        """{"error": "unknown"}"""
    }
    Spark.notFound { _, res -> res.status(404); """{"error": "not_found"}""" }

    Spark.path("/:id") {
        //VIEW FILE
        Spark.head("") { req, res ->
            var id = req.params(":id").toString()
            if (id.contains('.')) {
                //REMOVE ENDINGS LIKE .gif
                val splitted = id.split(".")
                id = splitted[0]
            }
            logger.info("Requested headers for image: $id")
            val header = getHeader(id, headerColl)
            if (header == null) {
                res.status(404)
                return@head """{"error": "not_found"}"""
            }

            res.header("Content-Type", header.contentType)
            res.header("Id", header.id)
            res.header("Content-Length", header.contentLength.toString())
            res.header("Total-Chunks", header.totalChunks.toString())
            res.header("Cache-Control", "no-cache")
            res.raw().setHeader("Date", Date(header.uploadedAt).toGMTString())
            """{"error": "null"}"""
        }

        Spark.get("") { req, res ->
            var id = req.params(":id").toString()
            if (id.contains('.')) {
                //REMOVE ENDINGS LIKE .gif
                val splitted = id.split(".")
                id = splitted[0]
            }
            logger.info("Requested image: $id")
            val header = getHeader(id, headerColl)
            if (header == null) {
                res.status(404)
                return@get """{"error": "not_found"}"""
            }
            val chunks = getAllChunks(id, chunkColl)
            if (chunks.isEmpty()) {
                res.status(500)
                return@get """{"error": "no_chunks_found"}"""
            }
            if (chunks.size != header.totalChunks) {
                res.status(500)
                return@get """{"error": "total_chunks_not_matching"}"""
            }
            val bytes = ByteArray(header.contentLength)
            var cursor = 0
            chunks.sortedBy { it.index }
            chunks.forEach {
                it.data.forEach {
                    bytes[cursor] = it
                    cursor++
                }
            }

            res.header("Content-Type", header.contentType)
            res.header("Id", header.id)
            res.header("Content-Length", header.contentLength.toString())
            res.header("Total-Chunks", header.totalChunks.toString())
            res.header("Cache-Control", "no-cache")
            res.raw().setHeader("Date", Date(header.uploadedAt).toGMTString())

            bytes
        }
        //DELETE FILE
        Spark.get("/d/:dkey") { req, res ->
            val id = req.params(":id").toString()
            val dkey = req.params(":dkey").toString()
            val header = getHeader(id, headerColl)
            if (header == null) {
                res.status(404)
                return@get """{"error": "not_found"}"""
            }

            if (header.deleteKey != dkey) {
                res.status(400)
                return@get """{"error": "delete_key_invalid"}"""
            }

            Thread {
                deleteHeader(id, headerColl)
                deleteAllChunks(id, chunkColl)
            }.start()
            logger.info("Deleted file: $id")
            """{"error": "null"}"""
        }
    }
    //UPLOAD FILE
    Spark.post("/u") { req, res ->
        if (req.headers("Auth") != configObj.uploadPw) {
            res.status(401)
            return@post """{"error": "unauthorized"}"""
        }
        val bytes = ByteArrayOutputStream()
        var finished = false
        val upload = ServletFileUpload()
        val iter : FileItemIterator
        try {
            iter = upload.getItemIterator(req.raw())
        } catch (e : FileUploadBase.InvalidContentTypeException) {
            res.status(400)
            return@post """{"error": "invalid_file"}"""
        }
        while (iter.hasNext() && !finished) {
            val item = iter.next()
            if (item.isFormField)
                continue
            val stream = item.openStream()
            var data = stream.read()
            while (data != -1) {
                bytes.write(data)
                data = stream.read()
            }
            stream.close()
            finished = true
        }
        val byteArr = bytes.toByteArray()
        bytes.close()
        val contentType : ContentInfo? = ContentInfoUtil().findMatch(byteArr)
        if (contentType == null) {
            res.status(406)
            return@post """{"error": "file_type_unknown"}"""
        }

        val mimeType = contentType.mimeType
        var newId : String
        do
            newId = RandomStringUtils.random(ID_LENGTH, true, true)
        while (getHeader(newId, headerColl) != null)
        val deleteKey = RandomStringUtils.random(16, true, true)
        val neededChunks = ceil(byteArr.size / configObj.splitSizeInBytes.toDouble())
        val chunks = mutableListOf<ChunkDoc>()
        var byteIndex = 0
        for (i in 0 until byteArr.size step configObj.splitSizeInBytes) {
            var end = i + configObj.splitSizeInBytes
            if (end > byteArr.size)
                end = byteArr.size
            var arr = ByteArray(end - i)
            for (j in 0 until end - i) {
                arr[j] = byteArr[byteIndex]
                byteIndex++
            }
            chunks.add(ChunkDoc(newId, chunks.size + 1, arr))
        }

        val type = if(contentType.fileExtensions == null)
            ""
        else
            "." + contentType.fileExtensions[0]

        val header = HeaderDoc(newId, deleteKey, mimeType, byteArr.size, Date().time, type, neededChunks.roundToInt())
        insertHeader(header, headerColl)
        insertAllChunks(chunks, chunkColl)

        logger.info("Uploaded file: $newId with a size of: ${byteArr.size} bytes and type of $type!")
        """{"error": "null", "id": "$newId", "delete_key": "$deleteKey", "type": "$type"}"""
    }

    logger.info("Server started!")
}
typealias MongoColl = MongoCollection<Document>

fun getHeader(id : String, headerColl : MongoColl) : HeaderDoc? {
    val doc = headerColl.find(Document("_id", id)).first()
    return gson.fromJson(doc?.toJson(json_settings), HeaderDoc::class.java)
}

fun getAllChunks(parentId : String, chunkColl : MongoColl) : List<ChunkDoc> {
    val list = mutableListOf<ChunkDoc>()

    chunkColl.find(Document("parent_id", parentId)).forEach {
        list.add(ChunkDoc(parentId, it.getInteger("index"), it.get("data", org.bson.types.Binary::class.java).data))
    }
    return list
}

fun deleteHeader(id : String, headerColl : MongoColl) : DeleteResult {
    return headerColl.deleteOne(Document("_id", id))
}

fun deleteAllChunks(parentId : String, chunkColl : MongoColl) : DeleteResult {
    return chunkColl.deleteMany(Document("parent_id", parentId))
}

fun insertHeader(header : HeaderDoc, headerColl : MongoColl) {
    headerColl.insertOne(Document.parse(gson.toJson(header)))
}

fun insertAllChunks(chunks : List<ChunkDoc>, chunkColl : MongoColl) {
    for (chunk in chunks) {
        val document = Document("parent_id", chunk.parentId)
                .append("index", chunk.index)
                .append("data", BsonBinary(chunk.data))
        chunkColl.insertOne(document)
    }
}