package com.example.giffer2.feature.home

import com.example.gifvision.LayerId

/** Identifies the primary sections surfaced in the GifVision home experience. */
enum class HomePage(val label: String) {
    Layer1("Layer 1"),
    Layer2("Layer 2"),
    MasterBlend("Master Blend");

    val layerId: LayerId?
        get() = when (this) {
            Layer1 -> LayerId.Layer1
            Layer2 -> LayerId.Layer2
            MasterBlend -> null
        }

    companion object {
        val ordered: List<HomePage> = values().toList()
    }
}
