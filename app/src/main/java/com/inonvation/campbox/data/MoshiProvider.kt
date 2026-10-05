package com.inonvation.campbox.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object MoshiProvider {
    val instance: Moshi by lazy {
        Moshi.Builder()
            .add(EmptyDataJsonAdapter())
            .add(LenientStringJsonAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
    }
}
