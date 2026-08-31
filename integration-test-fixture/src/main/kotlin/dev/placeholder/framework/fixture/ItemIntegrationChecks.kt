package dev.placeholder.framework.fixture

import dev.placeholder.framework.items.CustomItemCodec
import dev.placeholder.framework.items.CustomItemRead
import dev.placeholder.framework.items.ItemPresentation
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.items.HmacKey
import dev.placeholder.framework.items.HmacKeyring
import dev.placeholder.framework.items.PersistentValueCodecs
import dev.placeholder.framework.items.PersistentValueRead
import dev.placeholder.framework.items.customItem
import dev.placeholder.framework.items.item
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType

internal fun runItemIntegrationChecks() {
    val categoryKey = NamespacedKey("frameworkfixture", "category")
    val spec = item(Material.COMPASS) {
        name = Component.text("Integration compass")
        lore {
            line(Component.text("Lossless native round trip"))
            emptyLine()
        }
        glint()
        data(DataComponentTypes.MAX_STACK_SIZE, 16)
        persistent(categoryKey, "fixture", PersistentValueCodecs.StringUtf8)
    }

    val stack = Items.materialize(spec)
    check(stack.itemMeta.displayName() == Component.text("Integration compass"))
    check(stack.itemMeta.lore() == listOf(Component.text("Lossless native round trip"), Component.empty()))
    check(stack.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE) == true)
    check(stack.getData(DataComponentTypes.MAX_STACK_SIZE) == 16)
    check(
        stack.persistentDataContainer.get(categoryKey, PersistentDataType.BYTE_ARRAY)?.decodeToString() == "fixture",
    )
    check((Items.persistent(stack, categoryKey, PersistentValueCodecs.StringUtf8) as PersistentValueRead.Found).value == "fixture")

    val snapshot = Items.capture(stack)
    val restored = Items.materialize(snapshot)
    check(restored == stack) { "Native item capture did not restore an equal stack" }
    check(Items.capture(restored) == snapshot) { "Native capture was not canonical" }
    val decoded = ItemSnapshot.decode(snapshot.encode()) as? dev.placeholder.framework.items.ItemSnapshotDecode.Found
        ?: error("Stable item snapshot envelope did not decode")
    check(decoded.snapshot == snapshot)
    check(Items.materialize(decoded.snapshot) == stack)

    val edited = snapshot.edit { amount = 2 }
    val editedStack = Items.materialize(edited)
    check(editedStack.amount == 2)
    check(editedStack.isSimilar(stack)) { "Editing amount discarded unrelated item data" }
    check(Items.capture(editedStack) == edited) { "Amount-only snapshot edit was not canonical" }

    val action = Items.materialize(spec, ItemPresentation.MenuAction)
    val hidden = checkNotNull(action.getData(DataComponentTypes.TOOLTIP_DISPLAY)).hiddenComponents()
    check(DataComponentTypes.DAMAGE in hidden)
    check(DataComponentTypes.ATTRIBUTE_MODIFIERS in hidden)

    val token = customItem(NamespacedKey("frameworkfixture", "token")) {
        version = 1
        data(Utf8Codec)
        render { value ->
            item(Material.PAPER) { name = Component.text("Token: $value") }
        }
    }
    val tokenStack = token.create("market")
    val found = token.read(tokenStack) as? CustomItemRead.Found
        ?: error("Typed custom item was not recognized")
    check(found.data == "market")
    check(token.read(item(Material.PAPER).let(Items::materialize)) == CustomItemRead.NotThisItem)

    val presentationSigned = customItem(NamespacedKey("frameworkfixture", "signed_token")) {
        data(Utf8Codec)
        integrity(
            HmacKeyring(HmacKey("fixture", ByteArray(32) { 7 })),
            includePresentation = true,
        )
        render { value -> item(Material.PAPER) { name = Component.text(value) } }
    }
    val signedStack = presentationSigned.create("authentic")
    check(presentationSigned.read(signedStack) is CustomItemRead.Found)
    signedStack.editMeta { it.displayName(Component.text("tampered")) }
    check(presentationSigned.read(signedStack) is CustomItemRead.InvalidSignature)
}

private object Utf8Codec : CustomItemCodec<String> {
    override val id: String = "fixture-utf8-v1"
    override fun encode(value: String): ByteArray = value.encodeToByteArray()
    override fun decode(bytes: ByteArray): String = bytes.decodeToString()
}
