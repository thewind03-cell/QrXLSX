/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** Vẽ QR bằng Canvas: mỗi ô là số nguyên pixel nên cạnh luôn sắc nét, kèm vùng trắng viền 4 ô theo chuẩn. */
@Composable
fun QrView(qr: QrEncoder.Qr, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val total = qr.size + 8
        val cell = kotlin.math.floor(minOf(size.width, size.height) / total).coerceAtLeast(1f)
        val side = cell * total
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                if (qr.isDark(x, y)) {
                    var run = 1
                    while (x + run < qr.size && qr.isDark(x + run, y)) run++
                    drawRect(
                        Color.Black,
                        topLeft = Offset(left + (x + 4) * cell, top + (y + 4) * cell),
                        size = Size(cell * run, cell)
                    )
                    x += run
                } else {
                    x++
                }
            }
        }
    }
}
