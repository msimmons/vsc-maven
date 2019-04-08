package org.apache.maven.cli

import com.google.inject.AbstractModule
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.Option
import org.apache.commons.cli.ParseException
import org.apache.maven.Maven
import org.apache.maven.building.FileSource
import org.apache.maven.cli.configuration.ConfigurationProcessor
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor
import org.apache.maven.cli.internal.BootstrapCoreExtensionManager
import org.apache.maven.cli.internal.extension.model.CoreExtension
import org.apache.maven.cli.internal.extension.model.io.xpp3.CoreExtensionsXpp3Reader
import org.apache.maven.execution.*
import org.apache.maven.execution.scope.internal.MojoExecutionScopeModule
import org.apache.maven.extension.internal.CoreExports
import org.apache.maven.extension.internal.CoreExtensionEntry
import org.apache.maven.model.building.ModelProcessor
import org.apache.maven.properties.internal.EnvironmentUtils
import org.apache.maven.properties.internal.SystemProperties
import org.apache.maven.session.scope.internal.SessionScopeModule
import org.apache.maven.toolchain.building.DefaultToolchainsBuildingRequest
import org.apache.maven.toolchain.building.ToolchainsBuilder
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.PlexusConstants
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.classworlds.realm.ClassRealm
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.util.StringUtils
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferListener
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher
import org.sonatype.plexus.components.sec.dispatcher.SecUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.*

class MavenConnector(val workingDirectory: String) : ExecutionListener, TransferListener {

    val MULTIMODULE_PROJECT_DIRECTORY = "maven.multiModuleProjectDirectory"

    val USER_HOME = System.getProperty("user.home")

    val USER_MAVEN_CONFIGURATION_HOME = File(USER_HOME, ".m2")

    val DEFAULT_USER_TOOLCHAINS_FILE = File(USER_MAVEN_CONFIGURATION_HOME, "toolchains.xml")

    private val EXT_CLASS_PATH = "maven.ext.class.path"

    private val EXTENSIONS_FILENAME = ".mvn/extensions.xml"

    private val MVN_MAVEN_CONFIG = ".mvn/maven.config"

    private val classWorld: ClassWorld? = null

    private var modelProcessor: ModelProcessor? = null

    private lateinit var maven: Maven

    private lateinit var executionRequestPopulator: MavenExecutionRequestPopulator

    private var toolchainsBuilder: ToolchainsBuilder? = null

    private var dispatcher: DefaultSecDispatcher? = null

    private lateinit var configurationProcessors: Map<String, ConfigurationProcessor>


    private val cliManager = CLIManager()

    /**
     * Connect to the project using source download as the goal and returning the resulting project model
     */
    fun connect() : MavenExecutionResult {
        return execute(arrayOf("dependency:sources"))
    }

    fun runTasks(tasks: Array<String>) : MavenExecutionResult {
        return execute(tasks)
    }

    /**
     * Execute the given goal(s) and return the result
     */
    private fun execute(args: Array<String>): MavenExecutionResult {
        val allArgs = arrayOf("-q") + args
        System.setProperty(MULTIMODULE_PROJECT_DIRECTORY, workingDirectory)
        try {
            val cliRequest = CliRequest(allArgs, classWorld)
            return connect(cliRequest)
        } finally {
            if (classWorld != null) {
                for (realm in ArrayList(classWorld.realms)) {
                    try {
                        classWorld.disposeRealm(realm.id)
                    } catch (ignored: NoSuchRealmException) {
                        // can't happen
                    }
                }
            }
        }
    }

    // TODO need to externalize CliRequest
    fun connect(cliRequest: CliRequest): MavenExecutionResult {
        var localContainer: PlexusContainer? = null
        try {
            initialize(cliRequest)
            cli(cliRequest)
            properties(cliRequest)
            localContainer = container(cliRequest)
            configure(cliRequest)
            toolchains(cliRequest)
            populateRequest(cliRequest)
            encryption(cliRequest)
            repository(cliRequest)
            return execute(cliRequest)
        } finally {
            localContainer?.dispose()
        }
    }

    private fun initialize(cliRequest: CliRequest) {
        cliRequest.workingDirectory = workingDirectory
        try {
            cliRequest.multiModuleProjectDirectory = File(workingDirectory).canonicalFile
        } catch (e: IOException) {
            cliRequest.multiModuleProjectDirectory = File(workingDirectory).absoluteFile
        }
    }

    private fun cli(cliRequest: CliRequest) {

        val args = ArrayList<String>()
        var mavenConfig: CommandLine? = null
        val configFile = File(cliRequest.multiModuleProjectDirectory, MVN_MAVEN_CONFIG)

        if (configFile.isFile) {
            for (arg in String(Files.readAllBytes(configFile.toPath())).split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                if (!arg.isEmpty()) {
                    args.add(arg)
                }
            }

            mavenConfig = cliManager.parse(args.toTypedArray())
            val unrecongized = mavenConfig.argList
            if (!unrecongized.isEmpty()) {
                throw ParseException("Unrecognized maven.config entries: $unrecongized")
            }
        }

        if (mavenConfig == null) {
            cliRequest.commandLine = cliManager.parse(cliRequest.args)
        } else {
            cliRequest.commandLine = cliMerge(cliManager.parse(cliRequest.args), mavenConfig)
        }

    }

    private fun cliMerge(mavenArgs: CommandLine, mavenConfig: CommandLine): CommandLine {
        val commandLineBuilder = CommandLine.Builder()

        // the args are easy, cli first then config file
        for (arg in mavenArgs.args) {
            commandLineBuilder.addArg(arg)
        }
        for (arg in mavenConfig.args) {
            commandLineBuilder.addArg(arg)
        }

        // now add all options, except for -D with cli first then config file
        val setPropertyOptions = ArrayList<Option>()
        for (opt in mavenArgs.options) {
            if (CLIManager.SET_SYSTEM_PROPERTY.toString() == opt.opt) {
                setPropertyOptions.add(opt)
            } else {
                commandLineBuilder.addOption(opt)
            }
        }
        for (opt in mavenConfig.options) {
            commandLineBuilder.addOption(opt)
        }
        // finally add the CLI system properties
        for (opt in setPropertyOptions) {
            commandLineBuilder.addOption(opt)
        }
        return commandLineBuilder.build()
    }

    //Needed to make this method package visible to make writing a unit test possible
    //Maybe it's better to move some of those methods to separate class (SoC).
    private fun properties(cliRequest: CliRequest) {
        populateProperties(cliRequest.commandLine, cliRequest.systemProperties, cliRequest.userProperties)
    }

    private fun container(cliRequest: CliRequest): PlexusContainer {
        if (cliRequest.classWorld == null) {
            cliRequest.classWorld = ClassWorld("plexus.core", Thread.currentThread().contextClassLoader)
        }

        var coreRealm: ClassRealm? = cliRequest.classWorld.getClassRealm("plexus.core")
        if (coreRealm == null) {
            coreRealm = cliRequest.classWorld.realms.iterator().next()
        }

        val extClassPath = parseExtClasspath(cliRequest)

        val coreEntry = CoreExtensionEntry.discoverFrom(coreRealm)
        val extensions = loadCoreExtensions(cliRequest, coreRealm, coreEntry.exportedArtifacts)

        val containerRealm = setupContainerRealm(cliRequest.classWorld, coreRealm, extClassPath, extensions)

        val cc = DefaultContainerConfiguration().setClassWorld(cliRequest.classWorld)
                .setRealm(containerRealm).setClassPathScanning(PlexusConstants.SCANNING_INDEX).setAutoWiring(true)
                .setJSR250Lifecycle(true).setName("maven")

        val exportedArtifacts = HashSet(coreEntry.exportedArtifacts)
        val exportedPackages = HashSet(coreEntry.exportedPackages)
        for (extension in extensions) {
            exportedArtifacts.addAll(extension.exportedArtifacts)
            exportedPackages.addAll(extension.exportedPackages)
        }

        val exports = CoreExports(containerRealm, exportedArtifacts, exportedPackages)

        val container = DefaultPlexusContainer(cc, object : AbstractModule() {
            override fun configure() {
                bind(CoreExports::class.java).toInstance(exports)
            }
        })
        container.loggerManager.threshold = Logger.LEVEL_WARN

        // NOTE: To avoid inconsistencies, we'll use the TCCL exclusively for lookups
        container.lookupRealm = null
        Thread.currentThread().contextClassLoader = container.containerRealm

        for (extension in extensions) {
            container.discoverComponents(extension.classRealm, SessionScopeModule(container),
                    MojoExecutionScopeModule(container))
        }

        maven = container.lookup(Maven::class.java)

        executionRequestPopulator = container.lookup(MavenExecutionRequestPopulator::class.java)

        modelProcessor = createModelProcessor(container)

        configurationProcessors = container.lookupMap(ConfigurationProcessor::class.java)

        toolchainsBuilder = container.lookup(ToolchainsBuilder::class.java)

        dispatcher = container.lookup(SecDispatcher::class.java, "maven") as DefaultSecDispatcher

        return container
    }

    private fun loadCoreExtensions(cliRequest: CliRequest, containerRealm: ClassRealm?,
                                   providedArtifacts: Set<String>): List<CoreExtensionEntry> {
        if (cliRequest.multiModuleProjectDirectory == null) {
            return emptyList()
        }

        val extensionsFile = File(cliRequest.multiModuleProjectDirectory, EXTENSIONS_FILENAME)
        if (!extensionsFile.isFile) {
            return emptyList()
        }

        val extensions = readCoreExtensionsDescriptor(extensionsFile)
        if (extensions.isEmpty()) {
            return emptyList()
        }

        val cc = DefaultContainerConfiguration() //
                .setClassWorld(cliRequest.classWorld) //
                .setRealm(containerRealm) //
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX) //
                .setAutoWiring(true) //
                .setJSR250Lifecycle(true) //
                .setName("maven")

        val container = DefaultPlexusContainer(cc, object : AbstractModule() {
        })

        try {
            container.lookupRealm = null

            container.loggerManager.threshold = Logger.LEVEL_WARN

            Thread.currentThread().contextClassLoader = container.containerRealm

            executionRequestPopulator = container.lookup(MavenExecutionRequestPopulator::class.java)

            configurationProcessors = container.lookupMap(ConfigurationProcessor::class.java)

            configure(cliRequest)

            var request = DefaultMavenExecutionRequest.copy(cliRequest.request)

            request = populateRequest(cliRequest, request)

            request = executionRequestPopulator.populateDefaults(request)

            val resolver = container.lookup(BootstrapCoreExtensionManager::class.java)

            return Collections.unmodifiableList(resolver.loadCoreExtensions(request, providedArtifacts,
                    extensions))

        } finally {
            container.dispose()
        }
    }

    private fun readCoreExtensionsDescriptor(extensionsFile: File): List<CoreExtension> {
        val parser = CoreExtensionsXpp3Reader()

        BufferedInputStream(FileInputStream(extensionsFile)).use { ins ->
            return parser.read(ins).extensions
        }

    }

    private fun setupContainerRealm(classWorld: ClassWorld, coreRealm: ClassRealm?, extClassPath: List<File>,
                                    extensions: List<CoreExtensionEntry>): ClassRealm? {
        if (!extClassPath.isEmpty() || !extensions.isEmpty()) {
            val extRealm = classWorld.newRealm("maven.ext", null)

            extRealm.parentRealm = coreRealm

            for (file in extClassPath) {
                extRealm.addURL(file.toURI().toURL())
            }

            for (entry in extensions.reversed()) {
                val exportedPackages = entry.exportedPackages
                val realm = entry.classRealm
                for (exportedPackage in exportedPackages) {
                    extRealm.importFrom(realm, exportedPackage)
                }
                if (exportedPackages.isEmpty()) {
                    // sisu uses realm imports to establish component visibility
                    extRealm.importFrom(realm, realm.id)
                }
            }

            return extRealm
        }

        return coreRealm
    }

    private fun parseExtClasspath(cliRequest: CliRequest): List<File> {
        var extClassPath: String? = cliRequest.userProperties.getProperty(EXT_CLASS_PATH)
        if (extClassPath == null) {
            extClassPath = cliRequest.systemProperties.getProperty(EXT_CLASS_PATH)
        }

        val jars = ArrayList<File>()

        if (extClassPath?.isEmpty() == true) {
            for (jar in StringUtils.split(extClassPath, File.pathSeparator)) {
                val file = resolveFile(File(jar), cliRequest.workingDirectory)
                if (file != null) jars.add(file)
            }
        }

        return jars
    }

    //
    // This should probably be a separate tool and not be baked into Maven.
    //
    private fun encryption(cliRequest: CliRequest) {
        if (cliRequest.commandLine.hasOption(CLIManager.ENCRYPT_MASTER_PASSWORD)) {
            var passwd: String? = cliRequest.commandLine.getOptionValue(CLIManager.ENCRYPT_MASTER_PASSWORD)

            if (passwd == null) {
                val cons = System.console()
                val password = cons?.readPassword("Master password: ")
                if (password != null) {
                    // Cipher uses Strings
                    passwd = String(password)

                    // Sun/Oracle advises to empty the char array
                    java.util.Arrays.fill(password, ' ')
                }
            }

            val cipher = DefaultPlexusCipher()

            println(
                    cipher.encryptAndDecorate(passwd, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION))

            throw RuntimeException()
        } else if (cliRequest.commandLine.hasOption(CLIManager.ENCRYPT_PASSWORD)) {
            var passwd: String? = cliRequest.commandLine.getOptionValue(CLIManager.ENCRYPT_PASSWORD)

            if (passwd == null) {
                val cons = System.console()
                val password = cons?.readPassword("Password: ")
                if (password != null) {
                    // Cipher uses Strings
                    passwd = String(password)

                    // Sun/Oracle advises to empty the char array
                    java.util.Arrays.fill(password, ' ')
                }
            }

            var configurationFile = dispatcher?.configurationFile

            if (configurationFile?.startsWith("~") == true) {
                configurationFile = System.getProperty("user.home") + configurationFile.substring(1)
            }

            val file = System.getProperty(DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION, configurationFile)

            var master: String? = null

            val sec = SecUtil.read(file, true)
            if (sec != null) {
                master = sec.master
            }

            if (master == null) {
                throw IllegalStateException("Master password is not set in the setting security file: $file")
            }

            val cipher = DefaultPlexusCipher()
            val masterPasswd = cipher.decryptDecorated(master, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION)
            println(cipher.encryptAndDecorate(passwd, masterPasswd))

            throw RuntimeException()
        }
    }

    private fun repository(cliRequest: CliRequest) {
        if (cliRequest.commandLine.hasOption(CLIManager.LEGACY_LOCAL_REPOSITORY) || java.lang.Boolean.getBoolean(
                        "maven.legacyLocalRepo")) {
            cliRequest.request.isUseLegacyLocalRepository = true
        }
    }

    private fun execute(cliRequest: CliRequest): MavenExecutionResult {
        val request = executionRequestPopulator.populateDefaults(cliRequest.request)
        return maven.execute(request)
    }

    private fun configure(cliRequest: CliRequest) {
        //
        // We expect at most 2 implementations to be available. The SettingsXmlConfigurationProcessor implementation
        // is always available in the core and likely always will be, but we may have another ConfigurationProcessor
        // present supplied by the user. The rule is that we only allow the execution of one ConfigurationProcessor.
        // If there is more than one then we execute the one supplied by the user, otherwise we execute the
        // the default SettingsXmlConfigurationProcessor.
        //
        val userSuppliedConfigurationProcessorCount = configurationProcessors.size - 1
        when (userSuppliedConfigurationProcessorCount) {
            0 -> configurationProcessors[SettingsXmlConfigurationProcessor.HINT]?.process(cliRequest)
            1 ->
                for (entry in configurationProcessors.entries) {
                    val hint = entry.key
                    if (hint != SettingsXmlConfigurationProcessor.HINT) {
                        val configurationProcessor = entry.value
                        configurationProcessor.process(cliRequest)
                    }
            }
            else ->
            {
                val sb = StringBuilder(
                        String.format("\nThere can only be one user supplied ConfigurationProcessor, there are %s:\n\n",
                                userSuppliedConfigurationProcessorCount))
                for (entry in configurationProcessors.entries) {
                    val hint = entry.key
                    if (hint != SettingsXmlConfigurationProcessor.HINT) {
                        val configurationProcessor = entry.value
                        sb.append(String.format("%s\n", configurationProcessor.javaClass.name))
                    }
                }
                sb.append("\n")
                throw Exception(sb.toString())
            }
        }
    }

    private fun toolchains(cliRequest: CliRequest) {
        val userToolchainsFile = when (cliRequest.commandLine.hasOption(CLIManager.ALTERNATE_USER_TOOLCHAINS)) {
            true -> resolveFile(File(cliRequest.commandLine.getOptionValue(CLIManager.ALTERNATE_USER_TOOLCHAINS)), cliRequest.workingDirectory)
            else -> DEFAULT_USER_TOOLCHAINS_FILE
        }

        val globalToolchainsFile = when (cliRequest.commandLine.hasOption(CLIManager.ALTERNATE_GLOBAL_TOOLCHAINS)) {
            true -> resolveFile(File(cliRequest.commandLine.getOptionValue(CLIManager.ALTERNATE_GLOBAL_TOOLCHAINS)), cliRequest.workingDirectory)
            else -> DEFAULT_USER_TOOLCHAINS_FILE
        }

        cliRequest.request.globalToolchainsFile = globalToolchainsFile
        cliRequest.request.userToolchainsFile = userToolchainsFile

        val toolchainsRequest = DefaultToolchainsBuildingRequest()
        if (globalToolchainsFile?.isFile == true) {
            toolchainsRequest.globalToolchainsSource = FileSource(globalToolchainsFile)
        }
        if (userToolchainsFile?.isFile == true) {
            toolchainsRequest.userToolchainsSource = FileSource(userToolchainsFile)
        }

        val toolchainsResult = toolchainsBuilder?.build(toolchainsRequest)

        executionRequestPopulator.populateFromToolchains(cliRequest.request, toolchainsResult?.effectiveToolchains)
    }

    private fun populateRequest(cliRequest: CliRequest): MavenExecutionRequest {
        return populateRequest(cliRequest, cliRequest.request)
    }

    private fun populateRequest(cliRequest: CliRequest, request: MavenExecutionRequest): MavenExecutionRequest {
        val commandLine = cliRequest.commandLine
        val workingDirectory = cliRequest.workingDirectory
        val showErrors = cliRequest.showErrors

        // ----------------------------------------------------------------------
        // Now that we have everything that we need we will fire up plexus and
        // bring the maven component to life for use.
        // ----------------------------------------------------------------------

        if (commandLine.hasOption(CLIManager.BATCH_MODE)) {
            request.isInteractiveMode = false
        }

        var noSnapshotUpdates = false
        if (commandLine.hasOption(CLIManager.SUPRESS_SNAPSHOT_UPDATES)) {
            noSnapshotUpdates = true
        }

        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        val goals = commandLine.argList

        var recursive = true

        // this is the default behavior.
        var reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_FAST

        if (commandLine.hasOption(CLIManager.NON_RECURSIVE)) {
            recursive = false
        }

        when {
            commandLine.hasOption(CLIManager.FAIL_FAST) -> reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_FAST
            commandLine.hasOption(CLIManager.FAIL_AT_END) -> reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_AT_END
            commandLine.hasOption(CLIManager.FAIL_NEVER) -> reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_NEVER
        }
        if (commandLine.hasOption(CLIManager.OFFLINE)) {
            request.isOffline = true
        }

        var updateSnapshots = false

        if (commandLine.hasOption(CLIManager.UPDATE_SNAPSHOTS)) {
            updateSnapshots = true
        }

        var globalChecksumPolicy: String? = null

        if (commandLine.hasOption(CLIManager.CHECKSUM_FAILURE_POLICY)) {
            globalChecksumPolicy = MavenExecutionRequest.CHECKSUM_POLICY_FAIL
        } else if (commandLine.hasOption(CLIManager.CHECKSUM_WARNING_POLICY)) {
            globalChecksumPolicy = MavenExecutionRequest.CHECKSUM_POLICY_WARN
        }

        val baseDirectory = File(workingDirectory, "").absoluteFile

        // ----------------------------------------------------------------------
        // Profile Activation
        // ----------------------------------------------------------------------

        val activeProfiles = ArrayList<String>()

        val inactiveProfiles = ArrayList<String>()

        if (commandLine.hasOption(CLIManager.ACTIVATE_PROFILES)) {
            val profileOptionValues = commandLine.getOptionValues(CLIManager.ACTIVATE_PROFILES)
            if (profileOptionValues != null) {
                for (profileOptionValue in profileOptionValues) {
                    val profileTokens = StringTokenizer(profileOptionValue, ",")

                    while (profileTokens.hasMoreTokens()) {
                        val profileAction = profileTokens.nextToken().trim { it <= ' ' }

                        if (profileAction.startsWith("-") || profileAction.startsWith("!")) {
                            inactiveProfiles.add(profileAction.substring(1))
                        } else if (profileAction.startsWith("+")) {
                            activeProfiles.add(profileAction.substring(1))
                        } else {
                            activeProfiles.add(profileAction)
                        }
                    }
                }
            }
        }

        val transferListener = this
        val executionListener = this

        var alternatePomFile: String? = null
        if (commandLine.hasOption(CLIManager.ALTERNATE_POM_FILE)) {
            alternatePomFile = commandLine.getOptionValue(CLIManager.ALTERNATE_POM_FILE)
        }

        request.setBaseDirectory(baseDirectory).setGoals(goals).setSystemProperties(
                cliRequest.systemProperties).setUserProperties(cliRequest.userProperties).setReactorFailureBehavior(
                reactorFailureBehaviour) // default: fail fast
                .setRecursive(recursive) // default: true
                .setShowErrors(showErrors) // default: false
                .addActiveProfiles(activeProfiles) // optional
                .addInactiveProfiles(inactiveProfiles) // optional
                .setExecutionListener(executionListener).setTransferListener(
                        transferListener) // default: batch mode which goes along with interactive
                .setUpdateSnapshots(updateSnapshots) // default: false
                .setNoSnapshotUpdates(noSnapshotUpdates) // default: false
                .setGlobalChecksumPolicy(globalChecksumPolicy).multiModuleProjectDirectory = cliRequest.multiModuleProjectDirectory

        if (alternatePomFile != null) {
            var pom = resolveFile(File(alternatePomFile), workingDirectory)
            if (pom?.isDirectory == true) {
                pom = File(pom, "pom.xml")
            }

            request.pom = pom
        } else if (modelProcessor != null) {
            val pom = modelProcessor?.locatePom(baseDirectory)

            if (pom?.isFile == true) {
                request.pom = pom
            }
        }

        if ((request.pom != null) && (request.pom.parentFile != null)) {
            request.setBaseDirectory(request.pom.parentFile)
        }

        if (commandLine.hasOption(CLIManager.RESUME_FROM)) {
            request.resumeFrom = commandLine.getOptionValue(CLIManager.RESUME_FROM)
        }

        if (commandLine.hasOption(CLIManager.PROJECT_LIST)) {
            val projectOptionValues = commandLine.getOptionValues(CLIManager.PROJECT_LIST)

            val inclProjects = ArrayList<String>()
            val exclProjects = ArrayList<String>()

            if (projectOptionValues != null) {
                for (projectOptionValue in projectOptionValues) {
                    val projectTokens = StringTokenizer(projectOptionValue, ",")

                    while (projectTokens.hasMoreTokens()) {
                        val projectAction = projectTokens.nextToken().trim { it <= ' ' }

                        if (projectAction.startsWith("-") || projectAction.startsWith("!")) {
                            exclProjects.add(projectAction.substring(1))
                        } else if (projectAction.startsWith("+")) {
                            inclProjects.add(projectAction.substring(1))
                        } else {
                            inclProjects.add(projectAction)
                        }
                    }
                }
            }

            request.selectedProjects = inclProjects
            request.excludedProjects = exclProjects
        }

        when {
            commandLine.hasOption(CLIManager.ALSO_MAKE) && !commandLine.hasOption(CLIManager.ALSO_MAKE_DEPENDENTS) -> request.makeBehavior = MavenExecutionRequest.REACTOR_MAKE_UPSTREAM
            !commandLine.hasOption(CLIManager.ALSO_MAKE) && commandLine.hasOption(CLIManager.ALSO_MAKE_DEPENDENTS) -> request.makeBehavior = MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM
            commandLine.hasOption(CLIManager.ALSO_MAKE) && commandLine.hasOption(CLIManager.ALSO_MAKE_DEPENDENTS) -> request.makeBehavior = MavenExecutionRequest.REACTOR_MAKE_BOTH
        }

        val localRepoProperty = request.userProperties.getProperty(MavenCli.LOCAL_REPO_PROPERTY) ?: request.systemProperties.getProperty(MavenCli.LOCAL_REPO_PROPERTY)
        if (localRepoProperty != null) {
            request.setLocalRepositoryPath(localRepoProperty)
        }

        request.isCacheNotFound = true
        request.isCacheTransferError = false

        //
        // Builder, concurrency and parallelism
        //
        // We preserve the existing methods for builder selection which is to look for various inputs in the threading
        // configuration. We don't have an easy way to allow a pluggable builder to provide its own configuration
        // parameters but this is sufficient for now. Ultimately we want components like Builders to provide a way to
        // extend the command line to accept its own configuration parameters.
        //
        val threadConfiguration = if (commandLine.hasOption(CLIManager.THREADS))
            commandLine.getOptionValue(CLIManager.THREADS)
        else
            null

        if (threadConfiguration != null) {
            //
            // Default to the standard multithreaded builder
            //
            request.builderId = "multithreaded"

            if (threadConfiguration.contains("C")) {
                request.degreeOfConcurrency = calculateDegreeOfConcurrencyWithCoreMultiplier(threadConfiguration)
            } else {
                request.degreeOfConcurrency = Integer.valueOf(threadConfiguration)
            }
        }

        //
        // Allow the builder to be overridden by the user if requested. The builders are now pluggable.
        //
        if (commandLine.hasOption(CLIManager.BUILDER)) {
            request.builderId = commandLine.getOptionValue(CLIManager.BUILDER)
        }
        request.isInteractiveMode = false
        request.loggingLevel = Logger.LEVEL_WARN
        cliRequest.quiet = true
        return request
    }

    private fun calculateDegreeOfConcurrencyWithCoreMultiplier(threadConfiguration: String): Int {
        val procs = Runtime.getRuntime().availableProcessors()
        return (java.lang.Float.valueOf(threadConfiguration.replace("C", "")) * procs).toInt()
    }

    private fun resolveFile(file: File?, workingDirectory: String): File? {
        return when {
            file == null -> null
            file.isAbsolute -> file
            file.path.startsWith(File.separator) -> file.absoluteFile
            else -> File(workingDirectory, file.path).absoluteFile
        }
    }

    // ----------------------------------------------------------------------
    // System properties handling
    // ----------------------------------------------------------------------

    private fun populateProperties(commandLine: CommandLine, systemProperties: Properties, userProperties: Properties) {
        EnvironmentUtils.addEnvVars(systemProperties)

        // ----------------------------------------------------------------------
        // Options that are set on the command line become system properties
        // and therefore are set in the session properties. System properties
        // are most dominant.
        // ----------------------------------------------------------------------

        if (commandLine.hasOption(CLIManager.SET_SYSTEM_PROPERTY)) {
            val defStrs = commandLine.getOptionValues(CLIManager.SET_SYSTEM_PROPERTY)

            if (defStrs != null) {
                for (defStr in defStrs) {
                    setCliProperty(defStr, userProperties)
                }
            }
        }

        SystemProperties.addSystemProperties(systemProperties)

        // ----------------------------------------------------------------------
        // Properties containing info about the currently running version of Maven
        // These override any corresponding properties set on the command line
        // ----------------------------------------------------------------------

        val buildProperties = CLIReportingUtils.getBuildProperties()

        val mavenVersion = buildProperties.getProperty(CLIReportingUtils.BUILD_VERSION_PROPERTY)
        systemProperties.setProperty("maven.version", mavenVersion)

        val mavenBuildVersion = CLIReportingUtils.createMavenVersionString(buildProperties)
        systemProperties.setProperty("maven.build.version", mavenBuildVersion)
    }

    private fun setCliProperty(property: String, properties: Properties) {
        val name: String

        val value: String

        val i = property.indexOf('=')

        if (i <= 0) {
            name = property.trim { it <= ' ' }

            value = "true"
        } else {
            name = property.substring(0, i).trim { it <= ' ' }

            value = property.substring(i + 1)
        }

        properties.setProperty(name, value)

        // ----------------------------------------------------------------------
        // I'm leaving the setting of system properties here as not to break
        // the SystemPropertyProfileActivator. This won't harm embedding. jvz.
        // ----------------------------------------------------------------------

        System.setProperty(name, value)
    }

    private fun createModelProcessor(container: PlexusContainer): ModelProcessor {
        return container.lookup(ModelProcessor::class.java)
    }

    override fun forkFailed(event: ExecutionEvent) {
    }

    override fun projectStarted(event: ExecutionEvent) {
    }

    override fun mojoSucceeded(event: ExecutionEvent) {
    }

    override fun projectSkipped(event: ExecutionEvent) {
    }

    override fun forkedProjectFailed(event: ExecutionEvent) {
    }

    override fun forkSucceeded(event: ExecutionEvent) {
    }

    override fun sessionStarted(event: ExecutionEvent) {
    }

    override fun projectFailed(event: ExecutionEvent) {
    }

    override fun projectDiscoveryStarted(event: ExecutionEvent) {
    }

    override fun forkedProjectStarted(event: ExecutionEvent) {
    }

    override fun sessionEnded(event: ExecutionEvent) {
    }

    override fun forkStarted(event: ExecutionEvent) {
    }

    override fun projectSucceeded(event: ExecutionEvent) {
    }

    override fun mojoFailed(event: ExecutionEvent) {
    }

    override fun mojoSkipped(event: ExecutionEvent) {
    }

    override fun forkedProjectSucceeded(event: ExecutionEvent) {
    }

    override fun mojoStarted(event: ExecutionEvent) {
    }

    override fun transferStarted(event: TransferEvent) {
    }

    override fun transferInitiated(event: TransferEvent) {
    }

    override fun transferSucceeded(event: TransferEvent) {
    }

    override fun transferProgressed(event: TransferEvent) {
    }

    override fun transferCorrupted(event: TransferEvent) {
    }

    override fun transferFailed(event: TransferEvent) {
    }

}