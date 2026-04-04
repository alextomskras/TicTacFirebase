package com.example.tictacfirebase.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class User(
    val uid: String,
    val Request: String,
    val username: String,
    val profileImageUrl: String,
    val newToken: String
) : Parcelable {
    constructor() : this("", "", "", "", "")
}