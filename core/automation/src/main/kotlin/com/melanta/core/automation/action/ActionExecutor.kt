package com.melanta.core.automation.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.melanta.core.automation.apps.AppResolver
import com.melanta.core.automation.apps.ContactResolver
import com.melanta.core.automation.screen.AccessibilityUiNode
import com.melanta.core.automation.screen.Bounds
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.service.AccessibilityBridge
import com.melanta.core.common.DispatcherProvider
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Executa um [AgentAction] sobre a tela atual. Cada ação tem uma cadeia de fallbacks, porque
 * apps reais nem sempre expõem as ações de acessibilidade esperadas.
 */
class ActionExecutor @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val appResolver: AppResolver,
    private val contacts: ContactResolver,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot?): ActionResult = withContext(dispatchers.default) {
        val service = bridge.service.value ?: return@withContext ActionResult.Error("Acessibilidade desconectada")
        try {
            when (action) {
                is AgentAction.Click -> click(service, action.target, snapshot)
                is AgentAction.Type -> type(service, action, snapshot)
                is AgentAction.Scroll -> scroll(service, action, snapshot)
                is AgentAction.LaunchIntent -> launch(service, action.spec)
                is AgentAction.Navigate -> navigate(service, action.key)
                is AgentAction.Done -> ActionResult.Ok
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Falha ao executar $action", e)
            ActionResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // --- Click ---------------------------------------------------------------------------------

    private suspend fun click(service: AccessibilityService, target: Target, snapshot: ScreenSnapshot?): ActionResult {
        val resolved = resolve(service, target, snapshot) ?: return ActionResult.Error("Alvo não encontrado: $target")
        resolved.node?.let { node ->
            val clickable = generateSequence(node) { it.parent }.take(MAX_ANCESTORS).firstOrNull { it.isClickable }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult.Ok
        }
        val bounds = resolved.bounds ?: return ActionResult.Error("Alvo sem posição na tela")
        return if (tap(service, bounds.centerX, bounds.centerY)) ActionResult.Ok else ActionResult.Error("Gesto de toque recusado")
    }

    // --- Type ----------------------------------------------------------------------------------

    private suspend fun type(service: AccessibilityService, action: AgentAction.Type, snapshot: ScreenSnapshot?): ActionResult {
        val node = if (action.target != null) {
            val resolved = resolve(service, action.target, snapshot) ?: return ActionResult.Error("Campo não encontrado")
            resolved.node ?: return ActionResult.Error("Campo sem nó de acessibilidade")
        } else {
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ActionResult.Error("Nenhum campo focado")
        }
        val field = generateSequence(node) { it.parent }.take(MAX_ANCESTORS).firstOrNull { it.isEditable } ?: node
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
        }
        val typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) || paste(service, field, action.text)
        if (!typed) return ActionResult.Error("O campo não aceitou texto")

        if (action.submit) {
            field.refresh()
            val enter = AccessibilityAction.ACTION_IME_ENTER
            if (field.actionList.none { it.id == enter.id } || !field.performAction(enter.id)) {
                return ActionResult.Error("Texto digitado, mas o campo não aceita Enter")
            }
        }
        return ActionResult.Ok
    }

    private fun paste(service: AccessibilityService, field: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("melanta", text))
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // --- Scroll --------------------------------------------------------------------------------

    private suspend fun scroll(service: AccessibilityService, action: AgentAction.Scroll, snapshot: ScreenSnapshot?): ActionResult {
        val node = action.target?.let { resolve(service, it, snapshot)?.node }
            ?: largestScrollable(snapshot)
        if (node != null) {
            val container = generateSequence(node) { it.parent }.take(MAX_ANCESTORS).firstOrNull { it.isScrollable }
            if (container != null && scrollNode(container, action.direction)) return ActionResult.Ok
        }
        val area = node?.let(::boundsOf) ?: snapshot?.screenBounds ?: return ActionResult.Error("Nada rolável")
        return if (swipe(service, area, action.direction)) ActionResult.Ok else ActionResult.Error("Gesto de rolagem recusado")
    }

    private fun scrollNode(node: AccessibilityNodeInfo, direction: Direction): Boolean {
        val directional = when (direction) {
            Direction.Up -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.Down -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.Left -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.Right -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (node.actionList.any { it.id == directional.id } && node.performAction(directional.id)) return true
        val generic = when (direction) {
            Direction.Down, Direction.Right -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Direction.Up, Direction.Left -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(generic)
    }

    private fun largestScrollable(snapshot: ScreenSnapshot?): AccessibilityNodeInfo? =
        snapshot?.elements
            ?.filter { "scroll" in it.flags }
            ?.maxByOrNull { it.bounds.area }
            ?.let { (it.node as? AccessibilityUiNode)?.info }
            ?.takeIf { it.refresh() }

    // --- Intents -------------------------------------------------------------------------------

    private fun launch(service: AccessibilityService, spec: IntentSpec): ActionResult {
        val intent = when (spec) {
            is IntentSpec.OpenApp -> {
                val app = appResolver.resolve(spec.name) ?: return ActionResult.Error("App \"${spec.name}\" não encontrado")
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.packageName, app.activityName)
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            is IntentSpec.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
                .apply { spec.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            is IntentSpec.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, spec.seconds)
                .apply { spec.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            is IntentSpec.Url -> {
                val uri = Uri.parse(spec.url)
                if (uri.scheme != "https") return ActionResult.Error("Só links https são permitidos")
                Intent(Intent.ACTION_VIEW, uri)
            }
            // DIAL só abre o discador com o número: a ligação continua exigindo o toque do usuário.
            is IntentSpec.Dial -> {
                val number = phoneOf(spec.target).getOrElse { return ActionResult.Error(it.message.orEmpty()) }
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            }
            is IntentSpec.WebSearch -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, spec.query)
            is IntentSpec.AddContact -> Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, spec.name)
                .apply { spec.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) } }
            is IntentSpec.Settings -> Intent(settingsAction(spec.page))
            is IntentSpec.Sms -> {
                val number = phoneOf(spec.recipient).getOrElse { return ActionResult.Error(it.message.orEmpty()) }
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", spec.text)
            }
            is IntentSpec.WhatsApp -> {
                val number = phoneOf(spec.recipient).getOrElse { return ActionResult.Error(it.message.orEmpty()) }
                // wa.me exige o número internacional só com dígitos (sem "+").
                val uri = Uri.parse("https://wa.me/" + number.filter(Char::isDigit)).buildUpon()
                    .appendQueryParameter("text", spec.text).build()
                Intent(Intent.ACTION_VIEW, uri).apply { if (isInstalled(service, WHATSAPP)) setPackage(WHATSAPP) }
            }
            is IntentSpec.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(spec.to))
                .putExtra(Intent.EXTRA_SUBJECT, spec.subject)
                .putExtra(Intent.EXTRA_TEXT, spec.body)
            is IntentSpec.Event -> {
                val begin = spec.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, spec.title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + EVENT_DEFAULT_MS)
            }
            is IntentSpec.Navigate -> if (isInstalled(service, MAPS)) {
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(spec.destination))).setPackage(MAPS)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(spec.destination)))
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            service.startActivity(withoutChooser(service, intent))
            ActionResult.Ok
        } catch (e: ActivityNotFoundException) {
            if (spec is IntentSpec.WebSearch) {
                // Sem app que trate WEB_SEARCH: abre a busca no navegador.
                val url = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", spec.query).build()
                return launch(service, IntentSpec.Url(url.toString()))
            }
            ActionResult.Error("Nenhum app trata ${intent.action}")
        }
    }

    /**
     * Com mais de um app capaz (ex.: Chrome e Google para WEB_SEARCH) o Android abre o seletor,
     * onde um SLM erra fácil e "Sempre" mudaria um padrão do usuário. Escolhemos o primeiro
     * candidato na ordem de preferência do sistema.
     */
    private fun withoutChooser(service: AccessibilityService, intent: Intent): Intent {
        if (intent.component != null || intent.`package` != null) return intent
        val pm = service.packageManager
        val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (default != null && default.packageName != SYSTEM_RESOLVER_PACKAGE) return intent
        val first = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull() ?: return intent
        return Intent(intent).setClassName(first.activityInfo.packageName, first.activityInfo.name)
    }

    /** Número direto ou telefone do contato com esse nome (exige a permissão de Contatos). */
    private fun phoneOf(target: String): Result<String> {
        val trimmed = target.trim()
        if (trimmed.count(Char::isDigit) >= MIN_PHONE_DIGITS) {
            return Result.success(trimmed.filter { it.isDigit() || it == '+' })
        }
        if (!contacts.hasPermission()) {
            return Result.failure(IllegalStateException("Para usar o nome \"$trimmed\", permita o acesso aos Contatos em Permissões"))
        }
        return contacts.phoneFor(trimmed)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Contato \"$trimmed\" não encontrado"))
    }

    /** Painéis (Wi-Fi, internet, volume) mostram o botão de ligar/desligar sem sair do app atual. */
    private fun settingsAction(page: SettingsPage): String = when (page) {
        SettingsPage.Wifi -> Settings.Panel.ACTION_WIFI
        SettingsPage.Internet -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        SettingsPage.Sound -> Settings.Panel.ACTION_VOLUME
        SettingsPage.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS
        SettingsPage.Display -> Settings.ACTION_DISPLAY_SETTINGS
        SettingsPage.Battery -> Intent.ACTION_POWER_USAGE_SUMMARY
        SettingsPage.Location -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        SettingsPage.Apps -> Settings.ACTION_APPLICATION_SETTINGS
        SettingsPage.Notifications -> ALL_APPS_NOTIFICATION_SETTINGS
        SettingsPage.Storage -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        SettingsPage.Airplane -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        SettingsPage.DateTime -> Settings.ACTION_DATE_SETTINGS
        SettingsPage.Language -> Settings.ACTION_LOCALE_SETTINGS
    }

    private fun isInstalled(service: AccessibilityService, pkg: String): Boolean =
        service.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun navigate(service: AccessibilityService, key: NavKey): ActionResult {
        val action = when (key) {
            NavKey.Back -> AccessibilityService.GLOBAL_ACTION_BACK
            NavKey.Home -> AccessibilityService.GLOBAL_ACTION_HOME
            NavKey.Recents -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        return if (service.performGlobalAction(action)) ActionResult.Ok else ActionResult.Error("Navegação $key recusada")
    }

    // --- Resolução de alvo e gestos ------------------------------------------------------------

    private class Resolved(val node: AccessibilityNodeInfo?, val bounds: Bounds?)

    /** Nós do snapshot são reconsultados com `refresh()`; se sumiram, usamos a posição guardada. */
    private fun resolve(service: AccessibilityService, target: Target, snapshot: ScreenSnapshot?): Resolved? = when (target) {
        is Target.ById -> snapshot?.byId(target.index)?.let { element ->
            val node = (element.node as? AccessibilityUiNode)?.info?.takeIf { it.refresh() }
            Resolved(node, node?.let(::boundsOf) ?: element.bounds)
        }
        is Target.ByText -> service.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(target.text)
            ?.firstOrNull { it.isVisibleToUser }
            ?.let { Resolved(it, boundsOf(it)) }
        is Target.ByViewId -> service.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(target.resourceId)
            ?.firstOrNull { it.isVisibleToUser }
            ?.let { Resolved(it, boundsOf(it)) }
        is Target.ByCoordinates -> Resolved(null, Bounds(target.x, target.y, target.x + 1, target.y + 1))
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Bounds =
        android.graphics.Rect().also(node::getBoundsInScreen).let { Bounds(it.left, it.top, it.right, it.bottom) }

    private suspend fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(service, GestureDescription.StrokeDescription(path, 0, TAP_MS))
    }

    /** Rolar para baixo = arrastar o conteúdo para cima, do mesmo jeito que o dedo faria. */
    private suspend fun swipe(service: AccessibilityService, area: Bounds, direction: Direction): Boolean {
        val (fx, fy, tx, ty) = when (direction) {
            Direction.Down -> listOf(0.5f, 0.75f, 0.5f, 0.25f)
            Direction.Up -> listOf(0.5f, 0.25f, 0.5f, 0.75f)
            Direction.Right -> listOf(0.8f, 0.5f, 0.2f, 0.5f)
            Direction.Left -> listOf(0.2f, 0.5f, 0.8f, 0.5f)
        }
        val path = Path().apply {
            moveTo(area.left + area.width * fx, area.top + area.height * fy)
            lineTo(area.left + area.width * tx, area.top + area.height * ty)
        }
        return dispatch(service, GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
    }

    private suspend fun dispatch(service: AccessibilityService, stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null)) cont.resume(false)
        }

    private companion object {
        const val TAG = "ActionExecutor"
        const val SYSTEM_RESOLVER_PACKAGE = "android"
        const val WHATSAPP = "com.whatsapp"
        const val MAPS = "com.google.android.apps.maps"
        const val MIN_PHONE_DIGITS = 3
        const val EVENT_DEFAULT_MS = 60 * 60_000L
        const val ALL_APPS_NOTIFICATION_SETTINGS = "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"
        const val MAX_ANCESTORS = 6
        const val TAP_MS = 50L
        const val SWIPE_MS = 300L
    }
}
