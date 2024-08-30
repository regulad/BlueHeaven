package xyz.regulad.blueheaven.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

@Composable
fun PublicKeyQRCode(
    publicKeyParameters: Ed25519PublicKeyParameters,
    size: Pair<Int, Int> = Pair(300, 300),
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(publicKeyParameters) {
        isLoading = true
        qrBitmap = withContext(Dispatchers.Default) {
            generateQRCode(publicKeyParameters, size.first, size.second)
        }
        isLoading = false
    }

    Box(modifier = modifier.size(size.first.dp, size.second.dp), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Public Key QR Code",
                    modifier = Modifier.size(size.first.dp, size.second.dp)
                )
            }
        }
    }
}

private fun generateQRCode(
    publicKeyParameters: Ed25519PublicKeyParameters,
    width: Int,
    height: Int
): Bitmap {
    val publicKeyBytes = publicKeyParameters.encoded
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(publicKeyBytes.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, width, height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    return bitmap
}
