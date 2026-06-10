val extName = "Animakima"
val pkgNameSuffix = "ru.animakima"
val extClass = ".Animakima"
val extVersionCode = 1
val isNsfw = false

extra["extName"] = extName
extra["extClass"] = extClass
extra["extVersionCode"] = extVersionCode
extra["isNsfw"] = isNsfw

// common.gradle в актуальных форках ожидает extNames/versionId
extra["extNames"] = arrayOf(extName)
extra["versionId"] = 1

apply(from = "$rootDir/common.gradle")
