package com.termux.app.models

enum class UserAction(private val mName: String) {

    ABOUT("about"),
    REPORT_ISSUE_FROM_TRANSCRIPT("report issue from transcript");

    fun getName(): String {
        return mName
    }

}
