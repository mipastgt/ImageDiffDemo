package de.mpmediasoft.imagediff.imagediff_demo

class JVMPlatform {
    val name: String = "Java ${System.getProperty("java.version")}"
}

fun getPlatform() = JVMPlatform()