package gg.hideouthq.data

import gg.hideouthq.core.model.AppAbout
import java.util.Properties

class DesktopBuild private constructor(val about: AppAbout, val postHogProjectKey: String?) {
    companion object {
        private val Resource = "/${AppId.replace('.', '/')}/app.properties"

        fun read(): DesktopBuild {
            val properties = DesktopBuild::class.java.getResourceAsStream(Resource)!!.use { Properties().apply { load(it) } }
            return DesktopBuild(AppAbout(properties.getProperty("version")), properties.getProperty("posthog.projectKey"))
        }
    }
}
