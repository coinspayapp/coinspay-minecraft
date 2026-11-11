# CoinsPay for Minecraft

Sell ranks, kits and perks on your Minecraft server for crypto. A player types `/donate`,
picks an item, pays on the CoinsPay payment page, and the plugin runs the item's commands
from the console. The money goes straight to your wallets, with fees as low as 0.3%.

## Requirements

One jar for every platform below.

| | Minimum | Tested on |
|---|---|---|
| Spigot, Paper | 1.8.8 | 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.4 |
| Folia | | 1.21.4 |
| Velocity | 3.3 | 4.3.1 |
| BungeeCord (and forks) | | 26.1 |
| Java | 8, 17 for Velocity | 8, 21, 25 |

You also need a [CoinsPay](https://coinspay.app) account with a shop.

## Quick start

1. Download `CoinsPay-<version>.jar` from [Releases](https://github.com/coinspayapp/coinspay-minecraft/releases/latest).
2. In the CoinsPay dashboard open your shop → "API keys" → "Create key", access **"Invoices only"**.
3. Put the jar into `plugins/` and restart the server.
4. Paste the key into `plugins/CoinsPay/config.yml` (`api.key`) and describe your items under
   `packages` ([example](#items)).
5. Run `/coinspay reload`, then `/coinspay status`: it shows your shop and the key. Players can
   now type `/donate`.

Full installation guides, including networks and ranks: [English](docs/install-en.md) · [Русский](docs/install-ru.md).

**Test mode.** Create a test key (`cpk_test_…`) to try the store first. The payment page of a
test invoice has a "Pay" button, no coins move, and the plugin delivers the item as usual.

## How it works

1. `/donate` opens a chest menu with your items. On a proxy, or with `menu: chat`, it prints a
   list with buttons instead.
2. A click creates a CoinsPay invoice. The player gets a link in chat and a map with its QR code
   to scan with a phone.
3. The player pays in USDT, BTC, ETH, TRX, BNB, LTC, SOL and more on the CoinsPay page, in their
   language.
4. Within seconds of the payment the plugin runs the item's commands. Items that need the player
   online wait until they join.

The plugin asks the CoinsPay API about payments every few seconds, so the server needs no public
address or webhook.

### Coins and prices

Players pay with more than 30 coins and tokens on TRON, Ethereum, BNB Smart Chain, Solana,
Bitcoin and Litecoin, among them USDT, USDC, BTC, ETH, BNB, TRX, LTC and SOL. The full list is on
[coinspay.app/coins](https://coinspay.app/coins).

You set prices in USD, EUR, RUB or another currency CoinsPay accepts. CoinsPay converts the price
at the current rate and holds the coin amount for 30 minutes while the player opens their wallet.

### Why CoinsPay

- **The plugin is free.** CoinsPay takes a fee on payouts only: as low as 0.3%, lower as your
  volume grows, with no monthly fee. For the first 10 days after the first payment, payouts up to
  $500 carry no fee.
- **The money is yours at once.** Every payment goes on to your wallets, up to 5 per network with
  shares, say 70% to you and 30% to a partner.
- **No chargebacks.** A crypto payment cannot be reversed by a bank, so a delivered rank stays
  paid for.
- **The fee can be passed to the buyer** (`buyer-pays-fee: true`).

## Items

```yaml
packages:
  vip-30:
    name: "&6VIP for 30 days"
    icon: GOLD_INGOT
    price: "4.99"
    commands:
      - "lp user {player} parent addtemp vip 30d accumulate"
  fly-7:
    name: "&aFlight for 7 days"
    price: "2.00"
    duration: 7d
    commands: ["lp user {player} permission set essentials.fly true"]
    expire-commands: ["lp user {player} permission unset essentials.fly"]
```

- **Ranks and permissions** are commands of your permission plugin. They work for offline
  players, so delivery does not wait for the player to come online.
- **Items that last a time** (`duration`, `expire-commands`) end on schedule, even when the
  player is offline. Buying again extends the time.
- **Items that need the player online** (`require-online: true`), such as kits and `give`
  commands, wait until the player joins.

## Safety

- **Use an "Invoices only" API key.** It cannot make payouts, so a leaked `config.yml` does not
  put money at risk. The plugin warns in the console if the key has full access.
- **Commands come only from your config.** They are copied into the order when the invoice is
  created. Nothing from the network is ever run as a command.
- **An item is delivered only if all of these match:** the invoice is one this server created,
  its reference, currency, amount and mode (test or live).
- **The buyer is the player who typed `/donate`.** Their name and UUID come from the session,
  not from input.
- **Each item is delivered once.** The state is written to `plugins/CoinsPay/data.json` before
  any command runs. A crash in the middle of a delivery leaves the order for review
  (`/coinspay redeliver` or `/coinspay markdone`) and never delivers twice.

Mojang's usage guidelines forbid selling gameplay advantages (pay-to-win). You are responsible
for what your server sells.

## Commands

| Command | Permission | |
|---|---|---|
| `/donate [item]` | `coinspay.donate` (everyone) | the store |
| `/coinspay status` | `coinspay.admin` (op) | key, shop, queue, last API error |
| `/coinspay pending` | | unpaid, waiting and timed items |
| `/coinspay check <invoice>` | | ask CoinsPay about an invoice now |
| `/coinspay redeliver <invoice>` | | run an item's commands again |
| `/coinspay markdone <invoice>` | | mark delivered without running commands |
| `/coinspay give <player> <item>` | | send an online player an invoice |
| `/coinspay reload` | | reread `config.yml` and texts |

## Building

The build runs in Docker, so no local JDK is needed:

```sh
sh bin/gradle.sh build      # tests and dist/CoinsPay-<version>.jar
```

## License

GPL-3.0. The QR code generator is [Nayuki's](https://www.nayuki.io/page/qr-code-generator-library) (MIT).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
Minecraft is a trademark of Mojang AB.
