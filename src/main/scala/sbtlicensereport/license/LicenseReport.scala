package sbtlicensereport
package license

import sbt._
import sbt.io.Using

case class DepModuleInfo(organization: String, name: String, version: String) {
  override def toString = s"${organization} # ${name} # ${version}"
}
case class DepLicense(
    module: DepModuleInfo,
    license: LicenseInfo,
    homepage: Option[URL],
    configs: Set[String],
    originatingModule: DepModuleInfo
) {
  override def toString =
    s"$module ${homepage.map(url => s" from $url")} on $license in ${configs.mkString("(", ",", ")")}"
}

object DepLicense {
  implicit val ordering: Ordering[DepLicense] = Ordering.fromLessThan { case (l, r) =>
    if (l.license.category != r.license.category) l.license.category.name < r.license.category.name
    else {
      if (l.license.name != r.license.name) l.license.name < r.license.name
      else {
        l.module.toString < r.module.toString
      }
    }
  }
}

case class LicenseReport(licenses: Seq[DepLicense], orig: UpdateReport) {
  override def toString = s"""|## License Report ##
                              |${licenses.mkString("\t", "\n\t", "\n")}
                              |""".stripMargin
}

object LicenseReport {

  private def withPrintableFile(file: File)(f: (Any => Unit) => Unit): Unit = {
    IO.createDirectory(file.getParentFile)
    Using.fileWriter(java.nio.charset.Charset.defaultCharset, false)(file) { writer =>
      def println(msg: Any): Unit = {
        writer.write(msg.toString)
        // writer.newLine()
      }
      f(println _)
    }
  }

  def dumpLicenseReport(
      reportLicenses: Seq[DepLicense],
      config: LicenseReportConfiguration
  ): Unit = {
    import config._
    val ordered = reportLicenses.filter(l => licenseFilter(l.license.category)).sorted
    // TODO - Make one of these for every configuration?
    for (language <- languages) {
      val reportFile = new File(config.reportDir, s"${title}.${language.ext}")
      withPrintableFile(reportFile) { print =>
        print(language.documentStart(title, reportStyleRules))
        print(makeHeader(language))
        print(language.tableHeader("Notes", config.licenseReportColumns.map(_.columnName): _*))
        val rendered = (ordered map { dep =>
          val notesRendered = notes(dep.module) getOrElse ""
          (
            notesRendered,
            config.licenseReportColumns map (_.render(dep, language))
          )
        }).distinct

        for ((notes, rest) <- rendered) {
          print(language.tableRow(notes, rest: _*))
        }
        print(language.tableEnd)
        print(language.documentEnd())
      }
    }
  }

  def checkLicenses(
      reportLicenses: Seq[DepLicense],
      exclude: PartialFunction[DepModuleInfo, Boolean],
      allowed: Seq[LicenseCategory],
      log: Logger
  ): Unit = {
    val violators =
      reportLicenses.filterNot(dl => exclude.applyOrElse(dl.module, (_: DepModuleInfo) => false)).collect {
        case dep if !allowed.contains(dep.license.category) => dep
      }

    if (violators.nonEmpty) {
      log.error(
        violators.sorted
          .map(v => (v.license, v.module))
          .distinct
          .map { case (license, module) => s"${license.category.name}: ${module.toString}" }
          .mkString("Found non-allowed licenses among the dependencies:\n", "\n", "")
      )
      throw new sbt.MessageOnlyException(s"Found non-allowed licenses!")
    } else {
      log.info("Found only allowed licenses among the dependencies!")
    }
  }

  private def getModuleInfo(dep: ModuleReport): DepModuleInfo = {
    // TODO - null handling...
    DepModuleInfo(dep.module.organization, dep.module.name, dep.module.revision)
  }

  def makeReport(
      updateReport: UpdateReport,
      configs: Set[String],
      licenseSelection: Seq[LicenseCategory],
      overrides: DepModuleInfo => Option[LicenseInfo],
      exclusions: DepModuleInfo => Option[Boolean],
      originatingModule: DepModuleInfo,
      log: Logger
  ): LicenseReport = {
    makeReportImpl(updateReport, configs, licenseSelection, overrides, exclusions, originatingModule, log)
  }

  /**
   * given a set of categories and an array of ivy-resolved licenses, pick the first one from our list, or default to
   * 'none specified'.
   */
  private def pickLicense(
      categories: Seq[LicenseCategory]
  )(licenses: Vector[(String, Option[String])]): LicenseInfo = {
    // Even though the url is optional this field seems to always exist
    val licensesWithUrls = licenses.collect { case (name, Some(url)) => (name, url) }
    if (licensesWithUrls.isEmpty) {
      LicenseInfo(LicenseCategory.NoneSpecified, "", "")
    } else {
      // We look for a license matching the category in the order they are defined.
      // i.e. the user selects the licenses they prefer to use, in order, if an artifact is dual-licensed (or more)
      categories
        .flatMap(category =>
          licensesWithUrls.collectFirst {
            case (name, url) if category.unapply(name) =>
              LicenseInfo(category, name, url)
          }
        )
        .headOption
        .getOrElse {
          val license = licensesWithUrls(0)
          LicenseInfo(LicenseCategory.Unrecognized, license._1, license._2)
        }
    }
  }

  /** Picks a single license (or none) for this dependency. */
  private def pickLicenseForDep(
      dep: ModuleReport,
      configs: Set[String],
      categories: Seq[LicenseCategory],
      originatingModule: DepModuleInfo
  ): Option[DepLicense] = {
    val cs = dep.configurations
    val filteredConfigs = if (cs.isEmpty) cs else cs.filter(configs.map(ConfigRef.apply))

    if (dep.evicted || filteredConfigs.isEmpty)
      None
    else {
      val licenses = dep.licenses
      val homepage = dep.homepage.map(string => new URI(string).toURL)
      Some(
        DepLicense(
          getModuleInfo(dep),
          pickLicense(categories)(licenses),
          homepage,
          filteredConfigs.map(_.name).toSet,
          originatingModule
        )
      )
    }
  }

  private def getLicenses(
      report: UpdateReport,
      configs: Set[String],
      categories: Seq[LicenseCategory],
      originatingModule: DepModuleInfo
  ): Seq[DepLicense] = {
    for {
      dep <- report.allModuleReports
      report <- pickLicenseForDep(dep, configs, categories, originatingModule)
    } yield report
  }

  private def makeReportImpl(
      report: UpdateReport,
      configs: Set[String],
      categories: Seq[LicenseCategory],
      overrides: DepModuleInfo => Option[LicenseInfo],
      exclusions: DepModuleInfo => Option[Boolean],
      originatingModule: DepModuleInfo,
      log: Logger
  ): LicenseReport = {
    val licenses = getLicenses(report, configs, categories, originatingModule) filterNot { dep =>
      exclusions(dep.module).getOrElse(false)
    } map { l =>
      overrides(l.module) match {
        case Some(o) => l.copy(license = o)
        case _       => l
      }
    }
    // TODO - Filter for a real report...
    LicenseReport(licenses, report)
  }
}
