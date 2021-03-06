/*
 * This file is part of Piper for Burp Suite (https://github.com/silentsignal/burp-piper)
 * Copyright (c) 2018 Andras Veres-Szentkiralyi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package burp

import com.redpois0n.terminal.JTerminal
import org.zeromq.codec.Z85
import java.awt.*
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread


const val NAME = "Piper"
const val EXTENSION_SETTINGS_KEY = "settings"

data class MessageInfo(val content: ByteArray, val text: String, val headers: List<String>?, val url: URL?, val hrr: IHttpRequestResponse? = null) {
    val asContentExtensionPair: Pair<ByteArray, String?> get() {
        return content to fileExtension
    }

    val fileExtension: String? get() {
        if (url != null) {
            val match = Regex("\\.[a-z0-9]$", RegexOption.IGNORE_CASE).find(url.path)
            if (match != null) {
                return match.groups[0]!!.value
            }
        }
        headers?.filter { it.startsWith("content-type: ", ignoreCase = true) }?.forEach {
            val parts = it.split(' ')[1].trim(';').split('/')
            val ext = mimeTypes[parts[0]]?.get(parts[1]) ?: return@forEach
            return ".$ext"
        }
        return null
    }

    companion object {
        val mimeTypes: Map<String, Map<String, String>>

        init {
            val db = Piper.MimeTypes.parseFrom(BurpExtender::class.java.classLoader.getResourceAsStream("mime.pb"))
            mimeTypes = db.typeOrBuilderList.map { type ->
                type.name to type.subtypeList.map { subtype ->
                    subtype.name to subtype.extension
                }.toMap()
            }.toMap()
        }
    }
}

class BurpExtender : IBurpExtender, ITab, ListDataListener {

    private lateinit var callbacks: IBurpExtenderCallbacks
    private lateinit var helpers: IExtensionHelpers
    private lateinit var configModel: ConfigModel
    private val queue = Queue()
    private val tabs = JTabbedPane()

    override fun contentsChanged(p0: ListDataEvent?) = saveConfig()
    override fun intervalAdded(p0: ListDataEvent?)   = saveConfig()
    override fun intervalRemoved(p0: ListDataEvent?) = saveConfig()

    private inner class MessageViewerManager : RegisteredToolManager<Piper.MessageViewer, IMessageEditorTabFactory>(
            configModel.messageViewersModel, callbacks::removeMessageEditorTabFactory, callbacks::registerMessageEditorTabFactory) {
        override fun isModelItemEnabled(item: Piper.MessageViewer): Boolean = item.common.enabled

        override fun modelToBurp(modelItem: Piper.MessageViewer): IMessageEditorTabFactory = IMessageEditorTabFactory() { _, _ ->
            if (modelItem.usesColors) TerminalEditor(modelItem, helpers, callbacks)
            else TextEditor(modelItem, helpers, callbacks)
        }
    }

    private inner class MacroManager : RegisteredToolManager<Piper.MinimalTool, ISessionHandlingAction>(
            configModel.macrosModel, callbacks::removeSessionHandlingAction, callbacks::registerSessionHandlingAction) {
        override fun isModelItemEnabled(item: Piper.MinimalTool): Boolean = item.enabled

        override fun modelToBurp(modelItem: Piper.MinimalTool): ISessionHandlingAction = object : ISessionHandlingAction {
            override fun performAction(currentRequest: IHttpRequestResponse?, macroItems: Array<out IHttpRequestResponse>?) {
                modelItem.pipeMessage(Collections.singletonList(RequestResponse.REQUEST), currentRequest ?: return)
            }

            override fun getActionName(): String = modelItem.name
        }
    }

    private inner class HttpListenerManager : RegisteredToolManager<Piper.HttpListener, IHttpListener>(
            configModel.httpListenersModel, callbacks::removeHttpListener, callbacks::registerHttpListener) {
        override fun isModelItemEnabled(item: Piper.HttpListener): Boolean = item.common.enabled

        override fun modelToBurp(modelItem: Piper.HttpListener): IHttpListener = IHttpListener { toolFlag, messageIsRequest, messageInfo ->
            if ((messageIsRequest xor (modelItem.scope == Piper.HttpListenerScope.REQUEST))
                    || (modelItem.tool != 0 && (modelItem.tool and toolFlag == 0))) return@IHttpListener
            modelItem.common.pipeMessage(ConfigHttpListenerScope.fromHttpListenerScope(modelItem.scope).inputList, messageInfo, modelItem.ignoreOutput)
        }
    }

    private inner class IntruderPayloadProcessorManager : RegisteredToolManager<Piper.MinimalTool, IIntruderPayloadProcessor>(
            configModel.intruderPayloadProcessorsModel, callbacks::removeIntruderPayloadProcessor, callbacks::registerIntruderPayloadProcessor) {
        override fun isModelItemEnabled(item: Piper.MinimalTool): Boolean = item.enabled

        override fun modelToBurp(modelItem: Piper.MinimalTool): IIntruderPayloadProcessor = object : IIntruderPayloadProcessor {
            override fun processPayload(currentPayload: ByteArray, originalPayload: ByteArray, baseValue: ByteArray): ByteArray? =
                    if (modelItem.hasFilter() && !modelItem.filter.matches(MessageInfo(currentPayload, helpers.bytesToString(currentPayload),
                                    headers = null, url = null), helpers, callbacks)) null
                    else getStdoutWithErrorHandling(modelItem.cmd.execute(currentPayload), modelItem)

            override fun getProcessorName(): String = modelItem.name
        }
    }

    private abstract inner class RegisteredToolManager<M, B>(private val model: DefaultListModel<M>,
                                                    private val remove: (B) -> Unit,
                                                    private val add: (B) -> Unit) : ListDataListener {

        private val registeredInBurp: MutableList<B?> = model.map(this::modelToRegListItem).toMutableList()

        abstract fun isModelItemEnabled(item: M): Boolean
        abstract fun modelToBurp(modelItem: M): B

        private fun modelToRegListItem(modelItem: M): B? =
                if (isModelItemEnabled(modelItem)) modelToBurp(modelItem).apply(add) else null

        override fun contentsChanged(e: ListDataEvent) {
            for (i in e.index0 .. e.index1) {
                val currentRegistered = registeredInBurp[i]
                registeredInBurp[i] = modelToRegListItem(model[i])
                remove(currentRegistered ?: continue)
            }
        }

        override fun intervalAdded(e: ListDataEvent) {
            for (i in e.index0 .. e.index1) registeredInBurp.add(i, modelToRegListItem(model[i]))
        }

        override fun intervalRemoved(e: ListDataEvent) {
            for (i in e.index1 downTo e.index0) remove(registeredInBurp.removeAt(i) ?: continue)
        }
    }

    override fun registerExtenderCallbacks(callbacks: IBurpExtenderCallbacks) {
        this.callbacks = callbacks
        helpers = callbacks.helpers
        configModel = ConfigModel(loadConfig())

        configModel.menuItemsModel.addListDataListener(this)  // Menu items are loaded on-demand, thus saving the config is enough
        configModel.commentatorsModel.addListDataListener(this)  // Commentators are menu items as well, see above
        configModel.messageViewersModel.addListDataListener(MessageViewerManager())
        configModel.macrosModel.addListDataListener(MacroManager())
        configModel.httpListenersModel.addListDataListener(HttpListenerManager())
        configModel.intruderPayloadProcessorsModel.addListDataListener(IntruderPayloadProcessorManager())

        configModel.addPropertyChangeListener(PropertyChangeListener { saveConfig() })

        callbacks.setExtensionName(NAME)
        callbacks.registerContextMenuFactory {
            val messages = it.selectedMessages
            if (messages.isNullOrEmpty()) return@registerContextMenuFactory emptyList()
            val topLevel = JMenu(NAME)
            generateContextMenu(messages.asList(), topLevel::add, includeCommentators = true)
            if (topLevel.subElements.isEmpty()) return@registerContextMenuFactory emptyList()
            return@registerContextMenuFactory Collections.singletonList(topLevel as JMenuItem)
        }

        populateTabs(configModel, null)
        callbacks.addSuiteTab(this)
    }

    private fun Piper.MinimalTool.pipeMessage(rrList: List<RequestResponse>, messageInfo: IHttpRequestResponse, ignoreOutput: Boolean = false) {
        require(rrList.isNotEmpty())
        val body = rrList.map { rr ->
            val bytes = rr.getMessage(messageInfo)!!
            val headers = rr.getHeaders(bytes, helpers)
            val bo = if (this.cmd.passHeaders) 0 else rr.getBodyOffset(bytes, helpers)
            val body = if (this.cmd.passHeaders) bytes else {
                if (bo < bytes.size - 1) {
                    bytes.copyOfRange(bo, bytes.size)
                } else null // if the request has no body, passHeaders=false tools have no use for it
            }
            body to headers
        }
        val (lastBody, headers) = body.last()
        if (lastBody == null) return
        if (this.hasFilter() && !this.filter.matches(MessageInfo(lastBody, helpers.bytesToString(lastBody),
                        headers, try { helpers.analyzeRequest(messageInfo).url } catch (_: Exception) { null }),
                        helpers, callbacks)) return
        val input = body.mapNotNull(Pair<ByteArray?, List<String>>::first).toTypedArray()
        val replacement = getStdoutWithErrorHandling(this.cmd.execute(*input), this)
        if (!ignoreOutput) {
            rrList.last().setMessage(messageInfo,
                    if (this.cmd.passHeaders) replacement else helpers.buildHttpMessage(headers, replacement))
        }
    }

    private fun getStdoutWithErrorHandling(executionResult: Pair<Process, List<File>>, tool: Piper.MinimalTool): ByteArray =
        executionResult.processOutput { process ->
            if (configModel.developer) {
                val stderr = process.errorStream.readBytes()
                if (stderr.isNotEmpty()) {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    val ts = LocalDateTime.now().format(formatter)
                    callbacks.stderr.buffered().use {
                        it.bufferedWriter().use { w ->
                            w.newLine()
                            w.write("${tool.name} called ${tool.cmd.commandLine} at $ts and stderr was not empty:")
                            w.newLine()
                            w.newLine()
                        }
                        it.write(stderr)
                    }
                }
            }
            process.inputStream.readBytes()
        }

    private fun populateTabs(cfg: ConfigModel, parent: Component?) {
        val switchToCommentator = { tabs.selectedIndex = 4 }

        tabs.addTab("Message viewers", MessageViewerListEditor(cfg.messageViewersModel, parent,
                cfg.commentatorsModel, switchToCommentator))
        tabs.addTab("Context menu items", MinimalToolListEditor(cfg.menuItemsModel, parent,
                ::MenuItemDialog, Piper.UserActionTool::getDefaultInstance, UserActionToolFromMap, Piper.UserActionTool::toMap))
        tabs.addTab("Macros", MinimalToolListEditor(cfg.macrosModel, parent,
                ::MacroDialog, Piper.MinimalTool::getDefaultInstance, ::minimalToolFromMap, Piper.MinimalTool::toMap))
        tabs.addTab("HTTP listeners", MinimalToolListEditor(cfg.httpListenersModel, parent,
                ::HttpListenerDialog, Piper.HttpListener::getDefaultInstance, ::httpListenerFromMap, Piper.HttpListener::toMap))
        tabs.addTab("Commentators", MinimalToolListEditor(cfg.commentatorsModel, parent,
                ::CommentatorDialog, Piper.Commentator::getDefaultInstance, ::commentatorFromMap, Piper.Commentator::toMap))
        tabs.addTab("Intruder payload processors", MinimalToolListEditor(cfg.intruderPayloadProcessorsModel, parent,
                ::IntruderPayloadProcessorDialog, Piper.MinimalTool::getDefaultInstance, ::minimalToolFromMap, Piper.MinimalTool::toMap))
        tabs.addTab("Queue", queue)
        tabs.addTab("Load/Save configuration", createLoadSaveUI(cfg, parent))
        tabs.addTab("Developer", createDeveloperUI(cfg))
    }

    private fun createDeveloperUI(cfg: ConfigModel): Component =
            JCheckBox("show user interface elements suited for developers").apply {
                isSelected = cfg.developer
                cfg.addPropertyChangeListener(PropertyChangeListener { isSelected = cfg.developer })
                addChangeListener { cfg.developer = isSelected }
            }

    // ITab members
    override fun getTabCaption(): String = NAME
    override fun getUiComponent(): Component = tabs

    private data class MessageSource(val direction: RequestResponse, val includeHeaders: Boolean) : Comparable<MessageSource> {
        override fun compareTo(other: MessageSource): Int =
                compareValuesBy(this, other, MessageSource::direction, MessageSource::includeHeaders)
    }

    private fun generateContextMenu(messages: Collection<IHttpRequestResponse>, add: (Component) -> Component, includeCommentators: Boolean) {
        val msize = messages.size
        val plural = if (msize == 1) "" else "s"

        fun createSubMenu(msrc: MessageSource) : JMenu = JMenu("Process $msize ${msrc.direction.name.toLowerCase()}$plural")

        fun EnumMap<RequestResponse, JMenu>.addMenuItemIfNotNull(msrc: MessageSource, child: JMenuItem?) {
            if (child != null) {
                this.getOrPut(msrc.direction) { createSubMenu(msrc) }.add(child)
            }
        }

        val messageDetails = messagesToMap(messages)
        val categoryMenus = EnumMap<RequestResponse, JMenu>(RequestResponse::class.java)

        for (cfgItem in configModel.enabledMenuItems) {
            // TODO check dependencies
            if ((cfgItem.maxInputs != 0 && cfgItem.maxInputs < msize) || cfgItem.minInputs > msize) continue
            for ((msrc, md) in messageDetails) {
                val menuItem = createMenuItem(cfgItem.common, null, msrc, md, MessageInfoMatchStrategy.ALL) { performMenuAction(cfgItem, md) }
                categoryMenus.addMenuItemIfNotNull(msrc, menuItem)
                if (!cfgItem.common.cmd.passHeaders && !cfgItem.common.hasFilter() && !cfgItem.avoidPipe) {
                    configModel.enabledMessageViewers.forEach { mv ->
                        categoryMenus.addMenuItemIfNotNull(msrc, createMenuItem(mv.common, cfgItem.common, msrc, md, MessageInfoMatchStrategy.ALL) {
                            performMenuAction(cfgItem, md, mv)
                        })
                    }
                }
            }
        }

        val commentatorCategoryMenus = EnumMap<RequestResponse, JMenu>(RequestResponse::class.java)

        if (includeCommentators) configModel.enabledCommentators.forEach { cfgItem ->
            messageDetails.forEach { (msrc, md) ->
                val item = createMenuItem(cfgItem.common, null, msrc, md, MessageInfoMatchStrategy.ANY) {
                    performCommentator(cfgItem, md)
                }
                if (item != null) {
                    val commentatorMenu = commentatorCategoryMenus.getOrPut(msrc.direction) {
                        categoryMenus[msrc.direction]?.apply { addSeparator() }
                                ?: createSubMenu(msrc).apply { categoryMenus[msrc.direction] = this }
                    }
                    commentatorMenu.add(item)
                }
            }
        }

        categoryMenus.values.map(add)
        add(JMenuItem("Add to queue").apply { addActionListener { queue.add(messages) } })
    }

    private fun messagesToMap(messages: Collection<IHttpRequestResponse>): Map<MessageSource, List<MessageInfo>> {
        val messageDetails = TreeMap<MessageSource, List<MessageInfo>>()
        for (rr in RequestResponse.values()) {
            val miWithHeaders = ArrayList<MessageInfo>(messages.size)
            val miWithoutHeaders = ArrayList<MessageInfo>(messages.size)
            messages.forEach {
                val bytes = rr.getMessage(it) ?: return@forEach
                val headers = rr.getHeaders(bytes, helpers)
                val url = try { helpers.analyzeRequest(it).url } catch (_: Exception) { null }
                miWithHeaders.add(MessageInfo(bytes, helpers.bytesToString(bytes), headers, url, it))
                val bo = rr.getBodyOffset(bytes, helpers)
                if (bo < bytes.size - 1) {
                    // if the request has no body, passHeaders=false actions have no use for it
                    val body = bytes.copyOfRange(bo, bytes.size)
                    miWithoutHeaders.add(MessageInfo(body, helpers.bytesToString(body), headers, url, it))
                }
            }
            messageDetails[MessageSource(rr, true)] = miWithHeaders
            if (miWithoutHeaders.isNotEmpty()) {
                messageDetails[MessageSource(rr, false)] = miWithoutHeaders
            }
        }
        return messageDetails
    }

    private fun createMenuItem(tool: Piper.MinimalTool, pipe: Piper.MinimalTool?, msrc: MessageSource,
                               md: List<MessageInfo>, mims: MessageInfoMatchStrategy, action: () -> Unit): JMenuItem? {
        if (tool.cmd.passHeaders == msrc.includeHeaders && tool.isInToolScope(msrc.direction.isRequest) && tool.canProcess(md, mims, helpers, callbacks)) {
            return JMenuItem(tool.name + (if (pipe == null) "" else " | ${pipe.name}")).apply {
                addActionListener { action() }
            }
        } else return null
    }

    private class HttpRequestResponse(original: IHttpRequestResponse) : IHttpRequestResponse {

        private class HttpService(original: IHttpService) : IHttpService {
            private val host = original.host
            private val port = original.port
            private val protocol = original.protocol

            override fun getHost(): String = host
            override fun getPort(): Int = port
            override fun getProtocol(): String = protocol
        }

        private val comment = original.comment
        private val highlight = original.highlight
        private val httpService = HttpService(original.httpService)
        private val request = original.request.clone()
        private val response = original.response.clone()

        override fun getComment(): String = comment
        override fun getHighlight(): String = highlight
        override fun getHttpService(): IHttpService = httpService
        override fun getRequest(): ByteArray = request
        override fun getResponse(): ByteArray = response

        override fun setComment(comment: String?) {}
        override fun setHighlight(color: String?) {}
        override fun setHttpService(httpService: IHttpService?) {}
        override fun setRequest(message: ByteArray?) {}
        override fun setResponse(message: ByteArray?) {}
    }

    inner class Queue : JPanel(BorderLayout()), ListDataListener, ListCellRenderer<IHttpRequestResponse>, ListSelectionListener {
        private val model = DefaultListModel<IHttpRequestResponse>()
        private val pnToolbar = JPanel()
        private val listWidget = JList(model)
        private val btnProcess = JButton("Process")
        private val cr = DefaultListCellRenderer()

        fun add(values: Iterable<IHttpRequestResponse>) = values.map(::HttpRequestResponse).forEach(model::addElement)

        private fun toHumanReadable(value: IHttpRequestResponse): String {
            val req = helpers.analyzeRequest(value)
            val resp = helpers.analyzeResponse(value.response)
            val size = value.response.size - resp.bodyOffset
            val plural = if (size == 1) "" else "s"
            return "${resp.statusCode} ${req.method} ${req.url} (response size = $size byte$plural)"
        }

        private fun addButtons() {
            btnProcess.addActionListener {
                val pm = JPopupMenu()
                generateContextMenu(listWidget.selectedValuesList, pm::add, includeCommentators = false)
                val b = it.source as Component
                val loc = b.locationOnScreen
                pm.show(this, 0, 0)
                pm.setLocation(loc.x, loc.y + b.height)
            }

            listOf(createRemoveButton(listWidget, model), btnProcess).map(pnToolbar::add)
        }

        override fun getListCellRendererComponent(list: JList<out IHttpRequestResponse>?, value: IHttpRequestResponse, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            val c = cr.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            cr.text = toHumanReadable(value)
            return c
        }

        override fun valueChanged(p0: ListSelectionEvent?) { updateBtnEnableDisableState() }
        override fun contentsChanged(p0: ListDataEvent?)   { updateBtnEnableDisableState() }
        override fun intervalAdded  (p0: ListDataEvent?)   { updateBtnEnableDisableState() }
        override fun intervalRemoved(p0: ListDataEvent?)   { updateBtnEnableDisableState() }

        private fun updateBtnEnableDisableState() {
            btnProcess.isEnabled = !listWidget.isSelectionEmpty
        }

        init {
            listWidget.cellRenderer = this
            listWidget.addListSelectionListener(this)
            model.addListDataListener(this)

            addButtons()
            updateBtnEnableDisableState()
            add(pnToolbar, BorderLayout.NORTH)
            add(JScrollPane(listWidget), BorderLayout.CENTER)
        }
    }

    private fun loadConfig(): Piper.Config {
        val serialized = callbacks.loadExtensionSetting(EXTENSION_SETTINGS_KEY)
        return if (serialized == null)
        {
            val cfgMod = loadDefaultConfig()
            saveConfig(cfgMod)
            cfgMod
        } else {
            Piper.Config.parseFrom(decompress(unpad4(Z85.Z85Decoder(serialized))))
        }
    }

    private fun saveConfig(cfg: Piper.Config = configModel.serialize()) {
        val serialized = Z85.Z85Encoder(pad4(compress(cfg.toByteArray())))
        callbacks.saveExtensionSetting(EXTENSION_SETTINGS_KEY, serialized)
    }

    private fun performMenuAction(cfgItem: Piper.UserActionTool, messages: List<MessageInfo>,
                                  messageViewer: Piper.MessageViewer? = null) {
        thread {
            val (input, tools) = if (messageViewer == null) {
                messages.map(MessageInfo::asContentExtensionPair) to Collections.singletonList(cfgItem.common)
            } else {
                messages.map { msg ->
                    messageViewer.common.cmd.execute(msg.asContentExtensionPair).processOutput { process ->
                        process.inputStream.use { it.readBytes() }
                    } to null
                } to listOf(messageViewer.common, cfgItem.common)
            }
            cfgItem.common.cmd.execute(*input.toTypedArray()).processOutput { process ->
                if (!cfgItem.hasGUI) handleGUI(process, tools)
            }
        }.start()
    }

    private fun performCommentator(cfgItem: Piper.Commentator, messages: List<MessageInfo>) {
        messages.forEach { mi ->
            val hrr = mi.hrr ?: return@forEach
            if ((hrr.comment.isNullOrEmpty() || cfgItem.overwrite) &&
                    (!cfgItem.common.hasFilter() || cfgItem.common.filter.matches(mi, helpers, callbacks))) {
                val stdout = cfgItem.common.cmd.execute(mi.asContentExtensionPair).processOutput { process ->
                    process.inputStream.readBytes()
                }
                hrr.comment = String(stdout, Charsets.UTF_8)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main (args: Array<String>) {
            if (args.size > 2 && args[0] == "build-static") {
                val map = mutableMapOf<String, MutableList<Piper.MimeTypes.Subtype>>()
                File(args[1]).bufferedReader().use { input ->
                    input.forEachLine { line ->
                        if (line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t', ' ').filter(String::isNotEmpty)
                        val type = parts[0].split('/')
                        val subtypes = map.getOrPut(type[0]) { mutableListOf() }
                        val ext = parts[1]
                        subtypes.add(Piper.MimeTypes.Subtype.newBuilder().setName(type[1]).setExtension(ext).build())
                    }
                }
                val mt = Piper.MimeTypes.newBuilder()
                map.forEach { (typeName, subtypes) ->
                    mt.addType(Piper.MimeTypes.Type.newBuilder().setName(typeName).addAllSubtype(subtypes))
                }
                File(args[2]).writeBytes(mt.build().toByteArray())
                return
            }
            val be = BurpExtender()
            val cfg = loadDefaultConfig()
            val dialog = JDialog()
            be.populateTabs(ConfigModel(cfg), dialog)
            showModalDialog(900, 600, be.uiComponent, NAME, dialog, null)
        }
    }
}

class ConfigModel(config: Piper.Config = Piper.Config.getDefaultInstance()) {
    private val pcs = PropertyChangeSupport(this)

    val enabledMessageViewers get() = messageViewersModel.toIterable().filter { it.common.enabled }
    val enabledMenuItems get() = menuItemsModel.toIterable().filter { it.common.enabled }
    val enabledCommentators get() = commentatorsModel.toIterable().filter { it.common.enabled }

    val macrosModel = DefaultListModel<Piper.MinimalTool>()
    val messageViewersModel = DefaultListModel<Piper.MessageViewer>()
    val menuItemsModel = DefaultListModel<Piper.UserActionTool>()
    val httpListenersModel = DefaultListModel<Piper.HttpListener>()
    val commentatorsModel = DefaultListModel<Piper.Commentator>()
    val intruderPayloadProcessorsModel = DefaultListModel<Piper.MinimalTool>()

    private var _developer = config.developer
    var developer: Boolean
        get() = _developer
        set(value) {
            val old = _developer
            _developer = value
            pcs.firePropertyChange("developer", old, value)
        }

    init { fillModels(config) }

    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        pcs.addPropertyChangeListener(listener)
    }

    fun fillModels(config: Piper.Config) {
        fillDefaultModel(config.macroList,                                       macrosModel)
        fillDefaultModel(config.messageViewerList,                       messageViewersModel)
        fillDefaultModel(config.menuItemList,                                 menuItemsModel)
        fillDefaultModel(config.httpListenerList,                         httpListenersModel)
        fillDefaultModel(config.commentatorList,                           commentatorsModel)
        fillDefaultModel(config.intruderPayloadProcessorList, intruderPayloadProcessorsModel)
    }

    fun serialize(): Piper.Config = Piper.Config.newBuilder()
            .addAllMacro(macrosModel.toIterable())
            .addAllMessageViewer(messageViewersModel.toIterable())
            .addAllMenuItem(menuItemsModel.toIterable())
            .addAllHttpListener(httpListenersModel.toIterable())
            .addAllCommentator(commentatorsModel.toIterable())
            .addAllIntruderPayloadProcessor(intruderPayloadProcessorsModel.toIterable())
            .setDeveloper(developer)
            .build()
}

private fun createLoadSaveUI(cfg: ConfigModel, parent: Component?): Component {
    return JPanel().apply {
        add(JButton("Load/restore default config").apply {
            addActionListener {
                if (JOptionPane.showConfirmDialog(parent,
                                "This will overwrite your currently loaded configuration with the default one. Are you sure?",
                                "Confirm restoring default configuration", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    cfg.fillModels(loadDefaultConfig())
                }
            }
        })
        add(JButton("Export to YAML file"      ).apply { addActionListener { exportConfig(ConfigFormat.YAML,     cfg, parent) } })
        add(JButton("Export to ProtoBuf file"  ).apply { addActionListener { exportConfig(ConfigFormat.PROTOBUF, cfg, parent) } })
        add(JButton("Import from YAML file"    ).apply { addActionListener { importConfig(ConfigFormat.YAML,     cfg, parent) } })
        add(JButton("Import from ProtoBuf file").apply { addActionListener { importConfig(ConfigFormat.PROTOBUF, cfg, parent) } })
        }
    }

private fun exportConfig(fmt: ConfigFormat, cfg: ConfigModel, parent: Component?) {
    val fc = JFileChooser()
    fc.fileFilter = FileNameExtensionFilter(fmt.name, fmt.fileExtension)
    if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
        fc.selectedFile.writeBytes(fmt.serialize(cfg.serialize()))
    }
}

private fun importConfig(fmt: ConfigFormat, cfg: ConfigModel, parent: Component?) {
    val fc = JFileChooser()
    fc.fileFilter = FileNameExtensionFilter(fmt.name, fmt.fileExtension)
    if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        try {
            cfg.fillModels(fmt.parse(fc.selectedFile.readBytes()))
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(parent, e.message, "Error while importing ${fc.selectedFile}", JOptionPane.ERROR_MESSAGE)
        }
    }
}

private fun loadDefaultConfig(): Piper.Config {
    // TODO use more efficient Protocol Buffers encoded version
    val cfg = configFromYaml(BurpExtender::class.java.classLoader
            .getResourceAsStream("defaults.yaml").reader().readText())
    return Piper.Config.newBuilder()
            .addAllMacro                   (cfg.macroList                   .map { it.buildEnabled() })
            .addAllMenuItem                (cfg.menuItemList                .map { it.buildEnabled() })
            .addAllMessageViewer           (cfg.messageViewerList           .map { it.buildEnabled() })
            .addAllHttpListener            (cfg.httpListenerList            .map { it.buildEnabled() })
            .addAllCommentator             (cfg.commentatorList             .map { it.buildEnabled() })
            .addAllIntruderPayloadProcessor(cfg.intruderPayloadProcessorList.map { it.buildEnabled() })
            .build()
}

private fun handleGUI(process: Process, tools: List<Piper.MinimalTool>) {
    val terminal = JTerminal()
    val scrollPane = JScrollPane()
    scrollPane.setViewportView(terminal)
    val frame = JFrame()
    with(frame) {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        addKeyListener(terminal.keyListener)
        add(scrollPane)
        setSize(675, 300)
        title = tools.joinToString(separator = " | ", prefix = "$NAME - ", transform = Piper.MinimalTool::getName)
        isVisible = true
    }

    for (stream in arrayOf(process.inputStream, process.errorStream)) {
        thread {
            val reader = stream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                terminal.append("$line\n")
            }
        }.start()
    }
}