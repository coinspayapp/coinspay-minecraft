# CoinsPay for Minecraft: installation

The plugin sells your server's ranks, kits and perks for crypto. A player types `/donate`,
picks an item and pays on the CoinsPay page. The plugin runs the item's commands itself, and
the money goes straight to your wallets.

Supported:
- **Spigot and Paper 1.8.8 – 1.21;**
- **Folia;**
- **Velocity 3.3+ and BungeeCord networks.**

## 1. API key

1. Sign up at [coinspay.app](https://coinspay.app) and create a shop.
2. Add your payout wallets in the shop settings.
3. Open the shop → "API keys" → "Create key", access **"Invoices only"**.

A key with that access cannot make payouts, so a leaked `config.yml` puts no money at risk.

For a dry run, tick "Test key" (`cpk_test_…`). The payment page of a test invoice has a "Pay"
button and charges no real money.

## 2. Installation

1. Put `CoinsPay-<version>.jar` into `plugins/` and restart the server.
2. Open `plugins/CoinsPay/config.yml` and put the key into `api.key`.
3. Describe your items under `packages` (below).
4. Run `/coinspay reload`, then `/coinspay status`. It should show your shop and a "test" or
   "live" key with `invoices` access.

## 3. Items

```yaml
packages:
  vip-30:                       # item id: letters, digits, _ and -
    name: "&6VIP for 30 days"   # name in the menu and on the invoice, &-colours
    description:
      - "Gold name and /hat"
    icon: GOLD_INGOT            # menu item
    price: "4.99"               # price in `currency` (USD by default)
    commands:                   # console commands after payment, no leading /
      - "lp user {player} parent addtemp vip 30d accumulate"
```

Placeholders:
- `{player}` (the name);
- `{uuid}`;
- `{package}` (the item id);
- `{amount}`, `{currency}` (the price and its currency);
- `{invoice}` (the invoice number).

### Ranks and permissions (VIP, moderator, admin)

Your permission plugin grants the rights; CoinsPay only runs its command. Such commands work for
offline players too, so delivery does not wait for the player to join.

- **LuckPerms** keeps the time itself:
  `lp user {player} parent addtemp vip 30d accumulate`. With `accumulate`, buying again extends
  the rank.
- **Without LuckPerms**, CoinsPay can keep the time:

  ```yaml
    admin-7:
      name: "&cAdmin for 7 days"
      price: "10"
      duration: 7d               # 30d, 12h, 90m, 2w, 1mo
      commands: ["op {player}"]
      expire-commands: ["deop {player}"]
  ```

  When the time is up, `expire-commands` run even if the player is offline. Buying again extends
  the time.

### Items and kits

For `give` and kits, set `require-online: true`. If the player pays and leaves, they get the
item when they join again.

## 4. How a player buys

1. `/donate` opens the menu. With `menu: chat`, it prints a list with buttons in chat instead.
2. A click creates an invoice. The chat shows a payment link, and the player gets a map in hand
   with the link's QR code to scan with a phone.
3. The player pays on the CoinsPay page in USDT, BTC, ETH, TRX, BNB, LTC, SOL and other coins.
4. Seconds after the payment the plugin says "Payment received" and runs the commands.

The plugin asks CoinsPay about payments every `poll-seconds`, so the server needs no public
address or webhook.

## 5. Networks

- **The plugin on the proxy** (Velocity or BungeeCord): `/donate` works on every server, and the
  commands run on the proxy console. This suits LuckPerms on the proxy: `lpv` on Velocity, `lpb`
  on BungeeCord.
- **The plugin on the network's servers:** install it on the servers that need it. Give each
  server its own `server-id`; they may share a key. An item is delivered on the server where it
  was bought.

## 6. Commands

| Command | What it does |
|---|---|
| `/donate [item]` | the store |
| `/coinspay status` | key, shop, queue, last API error |
| `/coinspay pending` | unpaid and waiting invoices, running timed items |
| `/coinspay check <invoice>` | check an invoice now |
| `/coinspay redeliver <invoice>` | run an item's commands again |
| `/coinspay markdone <invoice>` | mark delivered without running commands |
| `/coinspay give <player> <item>` | send an online player an invoice |
| `/coinspay reload` | reread `config.yml` and the texts |

Permissions: `coinspay.donate` for everyone; `coinspay.admin` for operators.

## Good to know

- **Each item is delivered once.** If the server crashes in the middle of a delivery, the plugin
  does not deliver the item again. It writes the invoice number to the console. Check whether the
  player got the item, then run `/coinspay redeliver <invoice>` or
  `/coinspay markdone <invoice>`.
- **Offline-mode (cracked) servers.** There a name is not protected: anyone joining under that
  name gets the rights bought for it. Use an authentication plugin such as AuthMe.
- **Mojang's rules.** Mojang's usage guidelines forbid selling gameplay advantages
  (pay-to-win). The server owner is responsible for what the server sells.
- **Texts.** Player texts are in `messages_ru.yml` and `messages_en.yml`. The language follows
  the player's client; `language: ru` or `en` pins one.
