package pro.kotlinmultiplatform.knowcal.server.controller

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.generativeai.ContentMaker
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.google.cloud.vertexai.generativeai.PartMaker
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pro.kotlinmultiplatform.knowcal.server.dto.BaseResponse
import pro.kotlinmultiplatform.knowcal.server.dto.CaloriesResponse


@RestController
@RequestMapping("/api/images")
class ImageController {

    @Autowired
    private lateinit var storage: Storage

    private val bucketName = "pocs-image-bucket"

    private val projectId: String = "pocs-363811"
    private val location: String = "us-central1"
    private val modelName: String = "gemini-1.5-flash-001"

    @PostMapping("/upload")
    suspend fun uploadImage(@RequestParam("image") file: MultipartFile): ResponseEntity<BaseResponse<CaloriesResponse?>> =
        withContext(Dispatchers.IO) {
            val res = BaseResponse<CaloriesResponse?>()
            try {
                val fileExt = file.originalFilename?.split(".")?.lastOrNull() ?: "jpg"
                val fileName = "${System.currentTimeMillis()}.$fileExt"
                val blobId = BlobId.of(bucketName, fileName)
                val blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.contentType)
                    .build()
                storage.create(blobInfo, file.bytes)

                VertexAI(projectId, location).use { vertexAI ->
                    val imageUri = "gs://$bucketName/$fileName"
                    val model = GenerativeModel(modelName, vertexAI)
                    val response = model.generateContent(
                        ContentMaker.fromMultiModalData(
                            PartMaker.fromMimeTypeAndData("image/$fileExt", imageUri),
                            "if it is a food then identify the name, calories and its ingredients as comma separated string and if it is not a food the simply say not food also provide the response in a minified json format"
                        )
                    )

                    val cleanedJsonString = response
                        .candidatesList[0]
                        .content
                        .partsList[0]
                        .text
                        .replace("```json\n", "")
                        .replace("\n```", "")
                    if (cleanedJsonString.contains("not food", ignoreCase = true).not()) {
                        val jsonObject = JsonParser.parseString(cleanedJsonString).asJsonObject
                        val name = jsonObject.get("name").asString
                        val calories = jsonObject.get("calories").asString
                        val ingredients = jsonObject.get("ingredients").asString

                        res.code = HttpStatus.OK.value()
                        res.msg = "OK"
                        res.data = CaloriesResponse(
                            name = name,
                            calories = calories,
                            ingredients = ingredients,
                        )
                    } else {
                        res.code = HttpStatus.NOT_FOUND.value()
                        res.msg = "FOOD_NOT_FOUND"
                        res.data = null
                    }
                }

                ResponseEntity(res, HttpStatus.valueOf(res.code))
            } catch (e: Exception) {
                res.code = HttpStatus.INTERNAL_SERVER_ERROR.value()
                res.msg = e.printStackTrace().toString()
                res.data = null
                ResponseEntity(res, HttpStatus.INTERNAL_SERVER_ERROR)
            }
        }
}