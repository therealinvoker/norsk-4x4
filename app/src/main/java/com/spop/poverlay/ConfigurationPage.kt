package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.fourbyfour.ui.FourByFourControls


@Composable
fun ConfigurationPage(
    viewModel: ConfigurationViewModel,
    onOpenHistory: () -> Unit = {},
    onConnectHrm: () -> Unit = {},
) {
    val showPermissionInfo by remember { viewModel.showPermissionInfo }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (showPermissionInfo) {
            PermissionPage(viewModel::onGrantPermissionClicked)
        } else {
            StartServicePage(
                onClickedStartFourByFour = viewModel::onStartFourByFourClicked,
                onOpenHistory = onOpenHistory,
                onConnectHrm = onConnectHrm,
            )
        }
    }
}

@Composable
private fun StartServicePage(
    onClickedStartFourByFour: () -> Unit,
    onOpenHistory: () -> Unit,
    onConnectHrm: () -> Unit,
) {
    FourByFourControls(
        onStart = onClickedStartFourByFour,
        onOpenHistory = onOpenHistory,
        onConnectHrm = onConnectHrm,
    )
}

@Composable
private fun PermissionPage(onClickedGrantPermission: () -> Unit) {
    Text(
        text = "Grupetto Needs Permission To Draw Over Other Apps",

        fontSize = 40.sp,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "It uses this permission to draw an overlay with your bike's sensor data",
        fontSize = 20.sp,
        fontWeight = FontWeight.Normal
    )
    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = onClickedGrantPermission
    ) {
        Text(text = "Grant Permission")
    }
}