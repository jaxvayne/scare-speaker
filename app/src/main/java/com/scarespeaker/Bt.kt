package com.scarespeaker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** 블루투스 스피커 연결 확인 / 연결 시도 */
object Bt {

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** 지금 폰 소리가 나가는 블루투스 스피커 이름 (없으면 null) */
    fun connectedSpeaker(ctx: Context): String? {
        val am = ctx.getSystemService(AudioManager::class.java)
        val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: return null
        return d.productName?.toString()?.ifBlank { null } ?: "블루투스 스피커"
    }

    /** 이 폰에 페어링된 오디오 기기 목록 */
    @SuppressLint("MissingPermission")
    fun pairedAudio(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            ad.bondedDevices.filter {
                val c = it.bluetoothClass?.majorDeviceClass
                c == null || c == BluetoothClass.Device.Major.AUDIO_VIDEO || c == BluetoothClass.Device.Major.UNCATEGORIZED
            }.sortedBy { it.name ?: "" }
        } catch (_: Exception) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun nameOf(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: Exception) { "기기" }

    fun isOn(ctx: Context): Boolean =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * 페어링된 스피커에 연결 시도. 안드로이드가 막아 두면 false (그때는 설정 화면으로 안내)
     */
    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, device: BluetoothDevice, done: (Boolean) -> Unit) {
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return done(false)
        val ok = ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val r = try {
                    val m = BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
                    m.invoke(proxy, device) as? Boolean ?: false
                } catch (e: Exception) {
                    AppLog.d("블루투스 직접 연결 불가: ${e.cause?.message ?: e.message}")
                    false
                }
                try { ad.closeProfileProxy(BluetoothProfile.A2DP, proxy) } catch (_: Exception) {}
                done(r)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        if (!ok) done(false)
    }

    fun openSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
