/*
 * Copyright (c) 2026 Thanh Phong Do <thewind03@gmail.com>
 * All rights reserved.
 */
package com.kiemke.qrxlsx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vẽ QR bằng Canvas: mỗi ô là số nguyên pixel nên cạnh luôn sắc nét, kèm vùng trắng viền 4 ô theo chuẩn. */
@Composable
fun QrView(qr: QrEncoder.Qr, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val total = qr.size + 8
        val minPx = minOf(
            if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY,
            if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
        )
        val cellPx = kotlin.math.floor(minPx / total)
        if (cellPx < 2f) {
            Text(
                "Vùng hiển thị quá nhỏ để quét",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val cell = kotlin.math.floor(minOf(size.width, size.height) / total)
                if (cell < 2f) return@Canvas
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
    }
}
