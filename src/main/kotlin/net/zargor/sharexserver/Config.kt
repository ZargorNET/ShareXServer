package net.zargor.sharexserver

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private const val PATH = "./config.json"
private val c_gson = GsonBuilder().setPrettyPrinting().create()
lateinit var configObj : ConfigObj
    private set

data class ConfigObj(
        val host : String = "0.0.0.0",
        val port : Int = 8089,
        val uploadPw : String = "SecurePW",
        val splitSizeInBytes : Int = 8000,
        val mongoHost : String = "127.0.0.1",
        val mongoPort : Int = 27017,
        val mongoUser : String = "sharex",
        val mongoPassword : String = "securepw",
        val mongoAuthDB : String = "sharex",
        val mongoDatabase : String = "sharex",
        val mongoHeaderColl : String = "header_coll",
        val mongoChunkColl : String = "chunk_coll"
)

fun generateConfig() {
    var tempObj : ConfigObj
    val file = File(PATH)
    if (!file.exists()) {
        file.createNewFile();
        Thread.sleep(5000)

        tempObj = ConfigObj()
        Files.write(Paths.get(PATH), c_gson.toJson(tempObj).toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
    } else {
        val b = StringBuilder()
        Files.readAllLines(Paths.get(PATH)).forEach { b.append(it) }
        tempObj = c_gson.fromJson(b.toString(), ConfigObj::class.java)
    }
    configObj = tempObj
}
