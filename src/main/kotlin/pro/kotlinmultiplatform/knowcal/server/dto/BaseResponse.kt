package pro.kotlinmultiplatform.knowcal.server.dto

class BaseResponse<T> {
    var code: Int = 0
    var msg: String = ""
    var data: T? = null
}