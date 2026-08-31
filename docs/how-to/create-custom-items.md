# Create and recognize a typed custom item

This guide builds a voucher whose payload survives restarts and schema changes. Click behavior belongs in the events module, so the definition stays useful in menus, commands, inventories, and storage.

## Define the payload

Apply Kotlin Serialization in the plug-in build and declare the current data type:

```kotlin
@Serializable
data class VoucherData(
    val cents: Int,
    val issuedTo: String,
)
```

Use stable scalar values in durable payloads. Player names and live Paper objects are poor choices. Store a UUID string or another domain ID instead.

## Define the item

```kotlin
val Voucher = customItem<VoucherData>(
    NamespacedKey(plugin, "voucher"),
) {
    version = 1
    data(VoucherData.serializer())

    render { voucher ->
        item(Material.PAPER) {
            name = Component.text("Store voucher")
            lore {
                line(Component.text("Value: ${voucher.cents} cents"))
                line(Component.text("Issued to: ${voucher.issuedTo}"))
            }
            glint()
        }
    }
}
```

Create a stack at the point where it enters Paper:

```kotlin
val stack = Voucher.create(VoucherData(cents = 500, issuedTo = account.id))
player.inventory.addItem(stack)
```

## Recognize it in an event

Keep every invalid case explicit when the item has value:

```kotlin
@Events
class VoucherEvents(
    private val ledger: VoucherLedger,
) {
    @On
    fun PlayerInteractEvent.redeemVoucher() {
        val stack = item ?: return
        when (val voucher = Voucher.read(stack)) {
            is CustomItemRead.Found -> ledger.redeem(player.uniqueId, voucher.data)
            CustomItemRead.NotThisItem -> return
            is CustomItemRead.InvalidData -> ledger.reportBroken(stack, voucher.problem)
            is CustomItemRead.UnsupportedVersion -> ledger.reportFuture(stack, voucher.found)
            is CustomItemRead.MigrationFailed -> ledger.reportBroken(stack, voucher.problem)
            is CustomItemRead.InvalidSignature -> ledger.reportTampered(stack, voucher.problem)
        }
    }
}
```

Do not remove the stack before the ledger accepts the redemption. The item definition proves identity and decodes data. Your domain service still owns replay protection and the business transaction.

## Add a schema migration

Suppose version 2 replaces cents with a currency-aware amount. Keep the old type and serializer while version 1 items remain valid:

```kotlin
@Serializable
data class VoucherV1(val cents: Int, val issuedTo: String)

@Serializable
data class VoucherData(val minorUnits: Long, val currency: String, val issuedTo: String)

val Voucher = customItem<VoucherData>(NamespacedKey(plugin, "voucher")) {
    version = 2
    data(VoucherData.serializer())
    migrate(1, KotlinJsonItemCodec(VoucherV1.serializer())) { old ->
        VoucherData(old.cents.toLong(), "USD", old.issuedTo)
    }
    render(::renderVoucher)
}
```

`Voucher.read` now returns a current `VoucherData` for both versions. It sets `migrated` on `Found` when it used the conversion. Issue a replacement with `Voucher.create(found.data)` only after your domain operation succeeds.

## Rotate signing keys

Fetch keys from plug-in secrets or configuration. Keep the previous key in the verification set during the rotation window:

```kotlin
integrity(
    HmacKeyring(
        active = HmacKey("2026-08", secrets.currentVoucherKey),
        verificationKeys = listOf(HmacKey("2026-05", secrets.previousVoucherKey)),
    ),
)
```

Remove an old verification key only when every item signed with it should become invalid. Use `includePresentation = true` only when the displayed material and metadata are part of the item's authenticity contract.
