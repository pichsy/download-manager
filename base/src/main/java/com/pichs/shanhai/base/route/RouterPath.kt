package com.pichs.shanhai.base.route

object RouterPath {

    // 前缀
    private const val ROUTER_PREFIX = "/"

    // main
    const val MAIN_HOME = "${ROUTER_PREFIX}main/home"
    const val MAIN_LOGIN = "${ROUTER_PREFIX}main/login"
    const val MAIN_ABOUT = "${ROUTER_PREFIX}main/about"
    const val MAIN_SETTINGS = "${ROUTER_PREFIX}main/settings"


    // note
    const val NOTE_HOME = "${ROUTER_PREFIX}note/home"
    const val NOTE_EDITOR = "${ROUTER_PREFIX}note/noteEditor"

    // search
    const val SEARCH_HOME = "${ROUTER_PREFIX}search/home"

    // 小程序
    const val FAST_APP = "${ROUTER_PREFIX}fastapp/home"

    // 下载器首页
    const val DOWNLOAD_HOME = "${ROUTER_PREFIX}download/home"

    // 下载管理界面
    const val DOWNLOAD_MANAGER = "${ROUTER_PREFIX}download/manager"

    // 下载测试界面
    const val DOWNLOAD_TEST = "${ROUTER_PREFIX}download/test"

    // LLM
    const val LLM_MAIN = "${ROUTER_PREFIX}llm/main"
    const val LLM_CHAT = "${ROUTER_PREFIX}llm/chat"

    // Auth
    const val GOOGLE_AUTHENTICATOR = "${ROUTER_PREFIX}authenticator/authenticator"

    // Scanner
    const val QRCODE_MAKER = "${ROUTER_PREFIX}scanner/qrcodeMaker"
    const val SCANNER_PAGE = "${ROUTER_PREFIX}scanner/scannerPage"

    // wifi
    const val WIFI_PWD_LOOKER = "${ROUTER_PREFIX}wifi/wifiPwdLooker"
    const val WIFI_SETTINGS = "${ROUTER_PREFIX}wifi/wifiSettings"

    // chrome
    const val CHROME_PAGE = "${ROUTER_PREFIX}chrome/webPage"
    const val CHROME_HOME = "${ROUTER_PREFIX}chrome/home"

    // yeelight
    const val YEE_LIGHT_CONTROL_PAGE = "${ROUTER_PREFIX}yeelight/control"

    // pytorch
    const val PYTORCH_HOME = "${ROUTER_PREFIX}pytorch/home"

}