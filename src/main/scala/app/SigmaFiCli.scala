package ksingh.sigmabonds
package app

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.impl.{BlockchainContextBuilderImpl, NodeDataSourceImpl}
import org.ergoplatform.restapi.client.ApiClient
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.Token

import scala.util.Try

/**
 * The SigmaFi V2 command line, over a single Ergo node:
 *
 *   sbt "runMain ksingh.sigmabonds.app.SigmaFiCli <command> [options]"
 *
 * Run it from the repository root, where `contracts/` is. Without --send, an action is built, signed by
 * the node wallet and checked by the node, but never submitted.
 */
object SigmaFiCli {

  val Usage: String =
    """SigmaFi V2 CLI. It talks to one Ergo node, with the extra index enabled, and nothing else.
      |
      |Usage: sbt "runMain ksingh.sigmabonds.app.SigmaFiCli <command> [arguments] [options]"
      |
      |Node (each option can also be set by environment variable):
      |  --node <url>          the node's REST URL (SIGMAFI_NODE; default http://127.0.0.1:9053)
      |  --api-key <key>       the node's API key, for anything that uses its wallet (SIGMAFI_API_KEY)
      |  --wallet-pass <pass>  unlocks the node wallet if it is locked, and locks it again afterwards
      |                        (SIGMAFI_WALLET_PASS)
      |  --address <address>   the wallet address to lend, borrow and execute as (default: the change address)
      |  --fee <ERG>           the miner fee (default 0.001)
      |  --send                submit the transaction. Without it, the node only signs and checks it.
      |
      |Look around (no API key needed, except with --mine or for claims):
      |  info                               heights, protocol addresses and hashes, your wallet address
      |  orders [--mine]                    open borrower orders
      |  offers [--mine]                    open lender offers
      |  bonds [--mine] [--liquidatable]    live bonds, each checked against its bond asset's mint
      |  claims                             bond assets in your wallet whose bond has paid the vault
      |
      |Lender (each bond mints you its bond asset: the name and description are its EIP-4 metadata):
      |  create-offer <loan terms> (--maturity <height> | --term <blocks>)
      |               [--name <text>] [--description <text>]
      |  cancel-offer <offer box id>          take the principal back
      |  fill-order <order box id> [--name <text>] [--description <text>]
      |  claim <bond asset id>               collect a repaid or liquidated bond from the vault
      |
      |Borrower:
      |  create-order <loan terms> (--maturity <height> | --term <blocks> | --duration <blocks>)
      |               --duration makes an on-close order: the bond matures that many blocks after the fill
      |  cancel-order <order box id>
      |  close-offer <offer box id>
      |  repay <bond box id>
      |
      |Executor:
      |  liquidate <bond box id> [<bond box id> ...]  liquidate matured bonds and keep their batching fees
      |
      |Loan terms:
      |  --principal <ERG>                 lend ERG, or
      |  --principal-token <token id>:<n>  lend n units of one token, repaid in the same token
      |  --repayment <amount>              ERG for an ERG loan, token units for a token loan
      |  --collateral-erg <ERG>
      |  --collateral-token <token id>:<n> repeat for each token; an offer's bond must hold exactly these
      |  --batching-fee <ERG>              the most a liquidator may keep (default 0.002)
      |  --liquidation <name>              default MaturityLiquidation
      |""".stripMargin

  private val DefaultNode = "http://127.0.0.1:9053"
  private val DefaultBatchingFee = "0.05"
  private val DefaultName = "SigmaFi ZC-Bond"
  private val DefaultDescription =
    "SigmaFi V2 zero-coupon bond asset"

  def main(args: Array[String]): Unit = {
    val code =
      try {
        run(Args.parse(args))
        0
      } catch {
        case e: CliError =>
          System.err.println(s"error: ${e.message}")
          1
        case e: NodeError =>
          System.err.println(s"error: ${e.getMessage}")
          1
        case e: java.io.IOException =>
          System.err.println(s"error: cannot reach the node: ${e.getMessage}")
          1
        case e: org.ergoplatform.appkit.ErgoClientException =>
          System.err.println(s"error: ${e.getMessage}")
          1
      }
    sys.exit(code)
  }

  def run(a: Args): Unit = {
    if (a.command.isEmpty || a.flag("help")) {
      println(Usage)
      return
    }
    val nodeUrl = a.opt("node").orElse(env("SIGMAFI_NODE")).getOrElse(DefaultNode).stripSuffix("/")
    val apiKey = a.opt("api-key").orElse(env("SIGMAFI_API_KEY"))
    val node = new NodeApi(nodeUrl, apiKey)
    val network =
      if (node.info().hcursor.downField("network").as[String].toOption.exists(_.equalsIgnoreCase("testnet")))
        NetworkType.TESTNET
      else NetworkType.MAINNET
    val settings = AppSettings(
      address = a.opt("address"),
      walletPass = a.opt("wallet-pass").orElse(env("SIGMAFI_WALLET_PASS")),
      fee = a.opt("fee").map(parseErg).getOrElse(org.ergoplatform.appkit.Parameters.MinFee),
      send = a.flag("send"))

    // appkit 6.0.0's RestApiErgoClient.createWithoutExplorer throws, so the context is built over its node-only
    // data source directly. It reads /info and the last headers: the height, and the state to build against.
    val ctx = new BlockchainContextBuilderImpl(
      new NodeDataSourceImpl(new ApiClient(nodeUrl + "/", "ApiKeyAuth", apiKey.getOrElse(""))), network).build()
    val app = new SigmaFiApp(ctx, node, settings)
    try dispatch(app, node, a)
    finally app.close()
  }

  private def dispatch(app: SigmaFiApp, node: NodeApi, a: Args): Unit = {
    def one(what: String): String = a.positional match {
      case Seq(id) => id
      case _ => throw CliError(s"${a.command.get} takes one argument: the $what")
    }
    def name = a.opt("name").getOrElse(DefaultName)
    def description = a.opt("description").getOrElse(DefaultDescription)

    a.command.get match {
      case "info" => info(app, node, a)
      case "orders" => listOrders(app, node, a.flag("mine"))
      case "offers" => listOffers(app, node, a.flag("mine"))
      case "bonds" => listBonds(app, node, a.flag("mine"), a.flag("liquidatable"))
      case "claims" => listClaims(app, node)

      case "create-offer" =>
        val maturity = schedule(a, app) match {
          case FixedMaturity(h) => h
          case OnClose(_) => throw CliError("offers have a fixed maturity: use --maturity or --term")
        }
        app.submit(app.createOffer(loan(a), maturity, name, description))
      case "cancel-offer" => app.submit(app.cancelOffer(one("offer box id")))
      case "fill-order" => app.submit(app.fillOrder(one("order box id"), name, description))
      case "claim" => app.submit(app.claim(one("bond asset id")))

      case "create-order" => app.submit(app.createOrder(loan(a), schedule(a, app)))
      case "cancel-order" => app.submit(app.cancelOrder(one("order box id")))
      case "close-offer" => app.submit(app.closeOffer(one("offer box id")))
      case "repay" => app.submit(app.repay(one("bond box id")))

      case "liquidate" => app.submit(app.liquidate(a.positional))

      case other => throw CliError(s"unknown command $other; run with --help for the list")
    }
  }

  // ═══ LISTINGS ═════════════════════════════════════════════════════════════

  private def info(app: SigmaFiApp, node: NodeApi, a: Args): Unit = {
    val p = app.protocol
    val network = app.networkType
    println(s"Network: $network; height ${app.height}")
    indexStatus(node).foreach(println)
    println(s"BondAssetVault:     ${p.vault.address(network)}")
    println(s"  hash (every bond's CONST_VAULT_HASH): ${p.vault.hashedPropBytesHex}")
    println(s"ZC_ERG_Bond:        ${p.ergBond.address(network)}")
    println(s"  hash:             ${p.ergBond.hashedPropBytesHex}")
    println(s"ZC_Token_Bond template hash:  ${Base16.encode(p.tokenBondTemplateHash)}")
    println(s"ZC_Offer template hash:       ${Base16.encode(p.offerTemplate.templateHash)}")
    println(s"ZC_Order template hash:       ${Base16.encode(p.orderTemplate.templateHash)}")
    println(s"ZC_OrderOnClose template hash: ${Base16.encode(p.onCloseTemplate.templateHash)}")
    p.liquidations.foreach(l => println(s"Liquidation ${l.name}: ${Base16.encode(l.hash)}"))
    if (a.opt("api-key").orElse(env("SIGMAFI_API_KEY")).isDefined)
      println(s"Wallet address: ${app.myAddress}")
  }

  private def listOrders(app: SigmaFiApp, node: NodeApi, mine: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    val orders = app.orders().filter(o => !mine || app.isWalletHash(o.borrowerHash))
    if (orders.isEmpty) println("No open orders.")
    orders.foreach { o =>
      val bond = app.protocol.pairedBond(o.bondHash, o.principalTokens)
      println(s"Order ${o.box.id}  " + (o.schedule match {
        case FixedMaturity(_) => "(fixed maturity)"
        case OnClose(_) => "(on-close)"
      }))
      println(s"  borrow:      ${Fmt.principal(o.principalErg, o.principalTokens)}; repay " +
        bond.fold("?")(t => Fmt.amount(o.repayment, t)))
      println("  maturity:    " + (o.schedule match {
        case FixedMaturity(h) => s"height $h (${h - app.height} blocks from now)"
        case OnClose(d) => s"$d blocks after the fill"
      }))
      println(s"  collateral:  ${Fmt.contents(o.box.value, o.box.tokens)}")
      println(s"  batching fee ${Fmt.erg(o.batchingFee)}; liquidation ${liqName(app, o.liqScriptHash)}")
      if (bond.isEmpty) println("  WARNING: its bond contract does not match its principal; fill-order refuses it")
      if (app.isOwnHash(o.borrowerHash)) println("  (your order)")
    }
  }

  private def listOffers(app: SigmaFiApp, node: NodeApi, mine: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    val offers = app.offers().filter(o => !mine || app.isWalletHash(o.terms.lenderHash))
    if (offers.isEmpty) println("No open offers.")
    offers.foreach { o =>
      val t = o.terms
      val bond = app.protocol.pairedBond(t.bondHash, o.principalTokens)
      println(s"Offer ${o.box.id}  bond asset '${o.name}'")
      println(s"  lend:        ${Fmt.principal(o.principalErg, o.principalTokens)}; repay " +
        bond.fold("?")(tk => Fmt.amount(t.repayment, tk)))
      println(s"  maturity:    height ${t.maturity} (${t.maturity - app.height} blocks from now)")
      println(s"  collateral:  ${Fmt.contents(t.collateralErg, o.collateralTokens)}")
      println(s"  batching fee ${Fmt.erg(t.batchingFee)}; liquidation ${liqName(app, t.liqScriptHash)}")
      if (bond.isEmpty) println("  WARNING: its bond contract does not match its principal; close-offer refuses it")
      if (app.isOwnHash(t.lenderHash)) println("  (your offer)")
    }
  }

  private def listBonds(app: SigmaFiApp, node: NodeApi, mine: Boolean, liquidatable: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    def matured(b: BondInfo) = app.height + 1 >= b.maturity
    val bonds = app.bonds()
      .filter(b => !liquidatable || matured(b) && app.protocol.liquidationOf(b.liqScriptHash).nonEmpty)
      .filter(b => !mine || app.isWalletHash(b.borrowerHash) || app.walletHolds(b.asset))
    if (bonds.isEmpty) println("No bonds.")
    bonds.foreach { b =>
      println(s"Bond ${b.box.id}  bond asset ${b.asset}  " +
        app.verifyBond(b).fold(r => s"NOT VERIFIED: $r", _ => "verified"))
      println(s"  repay:       ${Fmt.amount(b.repayment, b.repayToken)} " + (
        if (matured(b)) s"(matured at ${b.maturity}: liquidatable by ${liqName(app, b.liqScriptHash)})"
        else s"before height ${b.maturity} (${b.maturity - app.height} blocks left)"))
      println(s"  collateral:  ${Fmt.contents(b.box.value, b.collateral)}; batching fee ${Fmt.erg(b.batchingFee)}")
      if (app.isOwnHash(b.borrowerHash)) println("  (you are its borrower)")
      if (mine && app.walletHolds(b.asset)) println("  (you hold its bond asset)")
    }
  }

  private def listClaims(app: SigmaFiApp, node: NodeApi): Unit = {
    indexStatus(node).foreach(System.err.println)
    val claimable = app.vaultBoxes().filter { case (asset, _) => app.walletHolds(asset) }
    if (claimable.isEmpty) println("Nothing to claim.")
    claimable.foreach { case (asset, boxes) =>
      println(s"Bond asset $asset: ${Fmt.contents(boxes.map(_.value).sum, boxes.flatMap(_.tokens))} " +
        s"in ${boxes.size} vault box(es). Claim with: claim $asset")
    }
  }

  private def liqName(app: SigmaFiApp, hash: Array[Byte]): String =
    app.protocol.liquidationOf(hash).fold(s"UNKNOWN (${Base16.encode(hash)})")(_.name)

  /** A warning when the node's index trails the chain, since every listing comes from it. */
  private def indexStatus(node: NodeApi): Option[String] = {
    val c = node.indexedHeight().hcursor
    for {
      indexed <- c.downField("indexedHeight").as[Int].toOption
      full <- c.downField("fullHeight").as[Int].toOption
      if indexed < full - 1
    } yield s"warning: the node's index is at height $indexed of $full; results may be incomplete"
  }

  // ═══ ARGUMENTS ════════════════════════════════════════════════════════════

  private def loan(a: Args): Loan = {
    val token = a.opt("principal-token").map(parseToken)
    val principalErg = (a.opt("principal"), token) match {
      case (Some(p), None) => parseErg(p)
      case (None, Some(_)) => Protocol.MinBox
      case (Some(_), Some(_)) => throw CliError("give --principal (an ERG loan) or --principal-token (a token loan), not both")
      case (None, None) => throw CliError("missing --principal <ERG> or --principal-token <token id>:<amount>")
    }
    val repayment = a.need("repayment")
    Loan(
      principalErg = principalErg,
      principalToken = token,
      repayment = if (token.isDefined) parseLong(repayment, "--repayment") else parseErg(repayment),
      batchingFee = parseErg(a.opt("batching-fee").getOrElse(DefaultBatchingFee)),
      collateralErg = parseErg(a.need("collateral-erg")),
      collateralTokens = a.opts("collateral-token").map(parseToken),
      liquidation = a.opt("liquidation").getOrElse("MaturityLiquidation"))
  }

  private def schedule(a: Args, app: SigmaFiApp): Schedule =
    Seq("maturity", "term", "duration").flatMap(k => a.opt(k).map(k -> _)) match {
      case Seq(("maturity", h)) => FixedMaturity(parseInt(h, "--maturity"))
      case Seq(("term", n)) => FixedMaturity(app.height + parseInt(n, "--term"))
      case Seq(("duration", n)) => OnClose(parseInt(n, "--duration"))
      case _ => throw CliError("give exactly one of --maturity <height>, --term <blocks> or --duration <blocks>")
    }

  def parseErg(s: String): Long = {
    val nano = Try(BigDecimal(s) * BigDecimal(1000000000L)).getOrElse(throw CliError(s"$s is not an ERG amount"))
    if (!nano.isWhole || nano < 0) throw CliError(s"$s is not an ERG amount (at most 9 decimals, not negative)")
    nano.toLongExact
  }

  private def parseLong(s: String, what: String): Long =
    Try(s.toLong).filter(_ > 0).getOrElse(throw CliError(s"$what takes a positive whole number, not $s"))

  private def parseInt(s: String, what: String): Int =
    Try(s.toInt).filter(_ > 0).getOrElse(throw CliError(s"$what takes a positive whole number, not $s"))

  private def parseToken(s: String): Token = s.split(":") match {
    case Array(id, n) if Try(ErgoId.create(id)).isSuccess && id.length == 64 => Token(ErgoId.create(id), parseLong(n, s"token $id"))
    case _ => throw CliError(s"$s is not <token id>:<amount>")
  }

  private def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)
}

/** Positional arguments, `--key value` (or `--key=value`) options, and bare flags. */
case class Args(command: Option[String], positional: Seq[String], options: Map[String, Seq[String]], flags: Set[String]) {
  def opt(key: String): Option[String] = options.get(key).map(_.last)
  def opts(key: String): Seq[String] = options.getOrElse(key, Seq.empty)
  def flag(key: String): Boolean = flags.contains(key)
  def need(key: String): String = opt(key).getOrElse(throw CliError(s"missing --$key"))
}

object Args {
  val Flags: Set[String] = Set("send", "mine", "liquidatable", "help")
  val Options: Set[String] = Set("node", "api-key", "wallet-pass", "address", "fee",
    "principal", "principal-token", "repayment", "collateral-erg", "collateral-token", "batching-fee",
    "liquidation", "maturity", "term", "duration", "name", "description")

  def parse(args: Seq[String]): Args = {
    val positional = Vector.newBuilder[String]
    val options = scala.collection.mutable.LinkedHashMap.empty[String, Vector[String]]
    val flags = Set.newBuilder[String]
    var rest = args.toList
    while (rest.nonEmpty) {
      rest match {
        case arg :: tail if arg.startsWith("--") =>
          val (key, inline) = arg.drop(2).split("=", 2) match {
            case Array(k, v) => (k, Some(v))
            case Array(k) => (k, None)
          }
          if (Flags.contains(key) && inline.isEmpty) {
            flags += key
            rest = tail
          } else if (Options.contains(key)) {
            val (value, after) = inline match {
              case Some(v) => (v, tail)
              case None => tail match {
                case v :: more => (v, more)
                case Nil => throw CliError(s"--$key needs a value")
              }
            }
            options(key) = options.getOrElse(key, Vector.empty) :+ value
            rest = after
          } else throw CliError(s"unknown option --$key; run with --help for the list")
        case arg :: tail =>
          positional += arg
          rest = tail
        case Nil =>
      }
    }
    val all = positional.result()
    Args(all.headOption, all.drop(1), options.toMap, flags.result())
  }
}
