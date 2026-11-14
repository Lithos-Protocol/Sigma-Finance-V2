package ksingh.sigmabonds
package app

import ksingh.sigmabonds.standard.{StandardApp, StandardCommands, StandardProtocol}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.appkit.impl.{BlockchainContextBuilderImpl, NodeDataSourceImpl}
import org.ergoplatform.restapi.client.ApiClient
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.Token

import java.util.Scanner
import scala.util.Try

/**
 * The SigmaFi V2 command line, over a single Ergo node. Started without a command it is a shell: it reads
 * one command per line until `exit`, so the JVM and the node connection start once.
 *
 *   sbt sigmafi                                      (the alias for runMain ksingh.sigmabonds.app.SigmaFiCli)
 *   sbt "runMain ksingh.sigmabonds.app.SigmaFiCli <command> [options]"   (one command, then exit)
 *
 * Commands build the ZC Standard contracts. `--old` routes a command to the first-generation contracts in
 * `contracts/old/`, which stay supported for boxes already on chain. Run it from the repository root. Without
 * --send, an action is built, signed by the node wallet and checked by the node, but never submitted.
 */
object SigmaFiCli {

  /** The CLI's floor for a lender-funded liquidation reserve, in both generations. */
  val MinimumReserve = "0.05"

  val Usage: String =
    s"""SigmaFi V2 CLI. It talks to one Ergo node, with the extra index enabled, and nothing else.
      |
      |Start the shell with `sbt sigmafi [node options]`, then type commands; `help` lists them, `exit` quits.
      |Or run one command: sbt "runMain ksingh.sigmabonds.app.SigmaFiCli <command> [arguments] [options]"
      |
      |Node (set once when starting the shell, or per command; each also reads an environment variable):
      |  --node <url>          the node's REST URL (SIGMAFI_NODE; default http://127.0.0.1:9053)
      |  --api-key <key>       the node's API key, for anything that uses its wallet (SIGMAFI_API_KEY)
      |  --wallet-pass <pass>  unlocks the node wallet if it is locked, and locks it again afterwards
      |                        (SIGMAFI_WALLET_PASS)
      |  --address <address>   the wallet address to lend, borrow and execute as (default: the change address)
      |  --fee <ERG>           the miner fee (default 0.001)
      |  --send                submit the transaction. Without it, the node only signs and checks it. In the
      |                        shell it applies only to the command it is typed with.
      |  --mempool             also read the mempool: boxes unconfirmed transactions create, less those they
      |                        spend (batch --run always does)
      |
      |ZC Standard (the default). Every transaction follows the zcs1 off-chain rules
      |(docs/ZC_STANDARD_OFFCHAIN.md): it discloses the scripts its boxes name by hash in context variable 64,
      |and bond units and shares carry zcs1 names and descriptions.
      |  info                               heights, pinned contract addresses and hashes, your wallet address
      |  orders [--mine]                    open borrow orders, every market
      |  bonds [--mine] [--liquidatable]    live bonds, with oracle ratios and whether each asset is genuine
      |  claims                             bond assets in your wallet whose bond has paid the vault
      |
      |  Borrower:
      |  create-order --principal <amount> --repayment <amount> --collateral-erg <ERG>
      |               (--maturity <height> | --term <blocks> | --duration <blocks>) [--exact-maturity]
      |               [--market ERG|SigUSD] [--collateral-token <token id>:<n> ...]
      |               [--liquidation maturity|oracle] [--ratio <percent>] [--oracle-age <blocks>]
      |               [--fill-ratio <percent> | --no-fill-guard] [--allow-liquidatable]
      |               [--expires-in <blocks>] [--expiry-fee <ERG>]
      |      Amounts are in the market's unit (ERG, or SigUSD with 2 decimals); a loan is lent and repaid in
      |      that one asset. A --maturity or --term at least 21600 blocks (about 30 days) out snaps to the
      |      nearest multiple of 21600, so bonds maturing the same month share one height; nearer ones and
      |      --exact-maturity keep the height. --duration makes an on-close order: the bond matures that many
      |      blocks after the fill. --liquidation oracle liquidates below --ratio (e.g. 150) of the repayment
      |      at a USD oracle price at most --oracle-age blocks old (default 60), as well as at maturity.
      |      An oracle order carries a fill guard: its contract refuses any fill that leaves the collateral
      |      under --fill-ratio of the repayment (default: --ratio) at a fresh oracle price, so it cannot be
      |      filled into a bond liquidatable at once. --no-fill-guard leaves it out; such an order already
      |      under its ratio is refused unless --allow-liquidatable.
      |      The order expires after --expires-in blocks (default 5040, or half a fixed maturity's term if
      |      less); then anyone may return it to you for the --expiry-fee (default 0.005 ERG, at least 0.002),
      |      which a fill refunds to you instead.
      |      Optional --nav [--margin <percent>] or --yield <percent> [--margin <percent>] creates a PricedOrder.
      |      Repayment stays fixed; --principal is its floor (and NAV anchor). Both support fixed or on-close maturity.
      |      Yield uses simple return per 262800 blocks; no calendar clock or compounding, fees excluded.
      |  cancel-order <order box id>
      |  repay <bond box id> [--from-collateral]
      |      --from-collateral repays out of the bond's own ERG or repayment token, as far as it holds them
      |  recollateralize <bond box id> [--add-erg <ERG>] [--add-token <token id>:<n> ...]
      |      at most once per 60 blocks; a token bond may add its repayment token even if it holds none
      |
      |  Lender:
      |  fill-order <order box id> [--fee-reserve <ERG>] [--allow-liquidatable]
      |      --fee-reserve: the liquidation reserve you fund in the bond (minimum and default $MinimumReserve).
      |      A fill that would start a bond under its own oracle ratio is refused unless --allow-liquidatable.
      |  claim <bond asset id>               collect a repaid or liquidated bond from the vault
      |
      |  Anyone:
      |  liquidate <bond box id> [...]       keeps each bond's fee reserve
      |  refund-order <order box id>        return an expired order to its borrower; keeps its expiry fee
      |  pack <box id> [...]                refund expired orders, offers and asks and liquidate bonds, any mix,
      |                                     in one transaction with one miner fee; keeps every fee
      |
      |  Shares (fractional, or with a Dutch auction of a liquidated bond's collateral):
      |  shares [--mine]                    share requests and vaults, with any open auction's price
      |  fractionalize <bond asset id> [--shares <N>] [--share-name <s>] [--share-description <s>]
      |               [--decimals <d>] [--pull-fee <ERG>] [--mint-fee <ERG>]
      |               [--dutch --auction-opening <percent> --auction-floor <percent> --auction-length <blocks>]
      |      deposits a bond unit you hold; any batcher mints the shares to you. Fees default to 0.005 ERG.
      |      zcs1 defaults: 100000000 shares with 6 decimals (1.000000 = 1% of the bond), and a name and
      |      description built from the bond, such as ZCS-Bond-Shares-3fa2c1. Overriding any of them is nonstandard.
      |  mint-shares <request box id>       anyone: mint a request's shares; keeps the mint fee
      |  cancel-share-request <request box id>
      |  pull <share vault box id>          anyone: pull a settled bond into its share vault; keeps the pull fee
      |  buy-lot <Dutch vault box id>       anyone: buy an open auction's lot at its current price
      |  claim-shares <share id> [--amount <n>]   all outstanding shares make a final claim
      |
      |  Asks (sell any token, paid in a market's asset):
      |  asks [--mine]
      |  sell <token id> --amount <n> [--market ERG|SigUSD] (--price <total> | --nav [--margin <percent>] | --yield <percent> [--margin <percent>]
      |               [--price <floor total>]) [--min-fill <n>] [--expires-in <blocks>] [--refund-fee <ERG>]
      |               [--min-cover <percent> [--oracle-age <blocks>]]
      |      --nav prices a bond unit or its shares at the bond's accrued value plus --margin (may be negative).
      |      --yield uses a PricedAsk at a simple buyer yield per 262800 blocks, fees excluded.
      |      Both policies accept signed --margin on the calculated price; this changes the resulting yield.
      |      With --nav or --yield, --price is an optional floor for the total amount.
      |      Shares' --min-fill defaults to the least lot that claims a raw unit of the repayment.
      |      --min-cover adds a sale guard to a bond unit or its shares, at either price: the ask sells only
      |      while the bond's collateral is worth at least that share of its repayment at an oracle price at
      |      most --oracle-age blocks old (default 30), so nobody buys it to liquidate it.
      |  buy <ask box id> [--amount <n>] [--allow-liquidatable]
      |      a claim on a bond under its own oracle ratio is refused unless --allow-liquidatable: liquidate it
      |  cancel-ask <ask box id>
      |  refund-ask <ask box id>            anyone, after expiry; keeps the refund fee
      |
      |  Lend offers (a yield bid that batchers fill from orders and asks):
      |  offers [--mine]
      |  create-offer --capital <amount> --yield <percent per 262800 blocks> --collateral-floor <percent>
      |               [--market ERG|SigUSD] [--min-blocks <n>] [--max-blocks <n>] [--min-price <amount>]
      |               [--batcher-fee <ERG>] [--fee-reserve <ERG>] [--oracle-age <blocks>] [--expires-in <blocks>]
      |               [--fees <ERG>] [--min-auction-floor <percent> --max-auction-length <blocks>]
      |               [--deliver-shares [share and --dutch options as for fractionalize]]
      |      Buys only bonds with the oracle liquidation rule: its policy measures their collateral. --fees is the
      |      ERG a SigUSD offer carries for fees and box minimums (default 0.5). --deliver-shares has a batcher
      |      mint each bond unit into shares for you; their zcs1 name leaves the bond out (ZCS-Bond-Shares),
      |      since the offer cannot know it yet.
      |  cancel-offer <offer box id>
      |  refund-offer <offer box id>        anyone, after expiry; keeps the batcher fee
      |  match-offer <offer box id> (--order <order box id> | --ask <ask box id> [--amount <n>])
      |      anyone: fill an order or buy from an ask with the offer; keeps the batcher fee
      |
      |  batch [--run]                      list every mint, pull, refund, liquidation and offer match that pays
      |                                     whoever runs it, and what it passed over because a box does not
      |                                     disclose a script it names. --run does the work in rounds: refunds
      |                                     and liquidations packed together, offers matched best yield first,
      |                                     and a fresh scan after each round (each submitted with --send)
      |
      |First generation (add --old to a command): info, orders, bonds, claims, fill-order [--batching-fee
      |<ERG>], claim, cancel-order, repay, liquidate. New orders use the ZC Standard only.
      |""".stripMargin

  private val DefaultNode = "http://127.0.0.1:9053"

  def main(args: Array[String]): Unit = {
    val a = Try(Args.parse(args)).fold(e => { report(e); sys.exit(1) }, identity)
    if (a.command.isEmpty && !a.flag("help")) {
      new Session(a).shell()
      sys.exit(0)
    } else sys.exit(if (new Session(a).execute(a)) 0 else 1)
  }

  /** Prints an error the way the person at the CLI should see it. */
  def report(e: Throwable): Unit = e match {
    case e: CliError => System.err.println(s"error: ${e.message}")
    case e: NodeError => System.err.println(s"error: ${e.getMessage}")
    case e: java.io.IOException => System.err.println(s"error: cannot reach the node: ${e.getMessage}")
    case e: org.ergoplatform.appkit.ErgoClientException => System.err.println(s"error: ${e.getMessage}")
    case e => System.err.println(s"error: ${e.getClass.getSimpleName}: ${e.getMessage}")
  }

  /**
   * One node connection: the launch options, the network, and the first generation's compiled contracts,
   * which compile once per JVM (the sigma compiler cannot compile a script twice).
   */
  class Session(launch: Args) {
    private val nodeUrl = launch.opt("node").orElse(env("SIGMAFI_NODE")).getOrElse(DefaultNode).stripSuffix("/")
    private var network: Option[NetworkType] = None
    private var oldProtocol: Option[Protocol] = None
    private lazy val standard = new StandardProtocol()
    /** Scripts seen and boxes spent so far: shared by every command this session runs. */
    private val scripts = new ScriptBook
    private val spent = scala.collection.mutable.Set.empty[String]
    /** Every transaction this session's commands submitted, in order. */
    val submissions: scala.collection.mutable.ArrayBuffer[Submission] = scala.collection.mutable.ArrayBuffer.empty

    /** Boxes a transaction made outside the CLI spent (the live test's setup), so no command builds on them. */
    def markSpent(boxIds: Seq[String]): Unit = spent ++= boxIds

    /** The node as the launch options name it; `mempool` also reads unconfirmed transactions. */
    def nodeApi(mempool: Boolean = false): NodeApi = new NodeApi(nodeUrl, apiKeyFor(launch), mempool)

    /** Reads commands until `exit` or the end of input. */
    def shell(): Unit = {
      println("SigmaFi V2 shell. Commands build the ZC Standard; add --old for the first-generation contracts.")
      println("Type `help` for the commands, `exit` to quit.")
      if (launch.flag("send")) println("note: --send at launch is ignored; type it with each command to submit")
      Try(connected(apiKeyFor(launch))).fold(report, n => println(s"Node $nodeUrl ($n)"))
      val in = new Scanner(System.in)
      var open = true
      while (open) {
        print("sigmafi> ")
        System.out.flush()
        if (!in.hasNextLine) open = false
        else in.nextLine().trim match {
          case "" =>
          case "exit" | "quit" => open = false
          case line => Try(Args.parse(Args.tokenize(line))).fold(report, a => execute(a.withDefaults(launch)))
        }
      }
    }

    /** Runs one command; false if it failed. A fresh context per command: the height moves between them. */
    def execute(a: Args): Boolean =
      try {
        if (a.command.isEmpty || a.command.contains("help") || a.flag("help")) println(Usage)
        else {
          // A batch run reads the mempool, so a later round finds what an earlier one submitted.
          val mempool = a.flag("mempool") || (a.command.contains("batch") && a.flag("run"))
          val hasApiKey = apiKeyFor(a).isDefined
          if (a.flag("old")) {
            val (node, ctx, settings) = parts(a, mempool)
            val app = new SigmaFiApp(ctx, node, settings, compiled = Some(old(ctx)))
            try OldCommands.dispatch(app, node, a, hasApiKey) finally app.close()
          } else withStandard(a, mempool) { (app, node) =>
            // A follow-up runs as its own command, with a fresh context, and keeps this one's node options,
            // --send and mempool reading.
            def followUp(line: String): Boolean =
              Try(Args.parse(Args.tokenize(line) ++ (if (a.flag("send")) Seq("--send") else Seq.empty) ++
                (if (mempool) Seq("--mempool") else Seq.empty)).withDefaults(a))
                .fold(e => { report(e); false }, execute)
            StandardCommands.dispatch(app, node, a, hasApiKey, followUp)
          }
        }
        true
      } catch {
        case e: Exception =>
          report(e)
          false
      }

    /**
     * Runs `f` with a ZC Standard app over a fresh context, as a command gets one: `a` gives the node options,
     * the address, fee and --send. Used by every standard command, and by code that drives the CLI from
     * outside the shell (the live test). The wallet is locked again afterwards if this unlocked it.
     */
    def withStandard[A](a: Args, mempool: Boolean)(f: (StandardApp, NodeApi) => A): A = {
      val (node, ctx, settings) = parts(a, mempool)
      val app = new StandardApp(ctx, node, settings, protocol = standard, scripts = scripts)
      try f(app, node) finally app.close()
    }

    private def parts(a: Args, mempool: Boolean): (NodeApi, BlockchainContext, AppSettings) = {
      val apiKey = apiKeyFor(a)
      val url = a.opt("node").map(_.stripSuffix("/")).getOrElse(nodeUrl)
      val node = new NodeApi(url, apiKey, mempool)
      val settings = AppSettings(
        address = a.opt("address"),
        walletPass = a.opt("wallet-pass").orElse(env("SIGMAFI_WALLET_PASS")),
        fee = a.opt("fee").map(parseErg).getOrElse(org.ergoplatform.appkit.Parameters.MinFee),
        send = a.flag("send"),
        spent = spent,
        submitted = submissions)
      (node, context(node, url, apiKey), settings)
    }

    private def apiKeyFor(a: Args): Option[String] = a.opt("api-key").orElse(env("SIGMAFI_API_KEY"))

    private def connected(apiKey: Option[String]): NetworkType =
      network.getOrElse {
        val n = if (new NodeApi(nodeUrl, apiKey).info().hcursor.downField("network").as[String].toOption
          .exists(_.equalsIgnoreCase("testnet"))) NetworkType.TESTNET else NetworkType.MAINNET
        network = Some(n)
        n
      }

    /**
     * appkit 6.0.0's RestApiErgoClient.createWithoutExplorer throws, so the context is built over its node-only
     * data source directly. It reads /info and the last headers: the height, and the state to build against.
     */
    private def context(node: NodeApi, url: String, apiKey: Option[String]): BlockchainContext =
      new BlockchainContextBuilderImpl(
        new NodeDataSourceImpl(new ApiClient(url + "/", "ApiKeyAuth", apiKey.getOrElse(""))), connected(apiKey)).build()

    private def old(ctx: BlockchainContext): Protocol = oldProtocol.getOrElse {
      val p = new Protocol(ctx)
      oldProtocol = Some(p)
      p
    }
  }

  // ═══ SHARED ARGUMENT PARSING ══════════════════════════════════════════════

  /** The one positional argument a command takes. */
  def one(a: Args, what: String): String = a.positional match {
    case Seq(id) => id
    case _ => throw CliError(s"${a.command.get} takes one argument: the $what")
  }

  /** A lender-funded liquidation reserve: at least the CLI's floor of 0.05 ERG. */
  def reserve(a: Args, option: String): Long = {
    val fee = parseErg(a.opt(option).getOrElse(MinimumReserve))
    if (fee < parseErg(MinimumReserve)) throw CliError(s"--$option must be at least $MinimumReserve ERG")
    fee
  }

  /** The first generation's `fill-order --batching-fee`. */
  def batchingFee(a: Args): Long = reserve(a, "batching-fee")

  def parseErg(s: String): Long = {
    val nano = Try(BigDecimal(s) * BigDecimal(1000000000L)).getOrElse(throw CliError(s"$s is not an ERG amount"))
    if (!nano.isWhole || nano < 0) throw CliError(s"$s is not an ERG amount (at most 9 decimals, not negative)")
    Try(nano.toLongExact).getOrElse(throw CliError(s"$s is too large an ERG amount"))
  }

  def parseLong(s: String, what: String): Long =
    Try(s.toLong).filter(_ > 0).getOrElse(throw CliError(s"$what takes a positive whole number, not $s"))

  def parseToken(s: String): Token = s.split(":") match {
    case Array(id, n) if id.length == 64 && Try(ErgoId.create(id)).isSuccess => Token(ErgoId.create(id), parseLong(n, s"token $id"))
    case _ => throw CliError(s"$s is not <token id>:<amount>")
  }

  /** --maturity <height>, --term <blocks> (from now) or --duration <blocks> (from the fill): exactly one. */
  def schedule(a: Args, height: Int): Schedule =
    Seq("maturity", "term", "duration").flatMap(k => a.opt(k).map(k -> _)) match {
      case Seq(("maturity", h)) => FixedMaturity(parseLong(h, "--maturity"))
      case Seq(("term", n)) => FixedMaturity(Try(Math.addExact(height.toLong, parseLong(n, "--term")))
        .getOrElse(throw CliError("--term overflows a Long")))
      case Seq(("duration", n)) => OnClose(parseLong(n, "--duration"))
      case _ => throw CliError("give exactly one of --maturity <height>, --term <blocks> or --duration <blocks>")
    }

  /** A warning when the node's index trails the chain, since every listing comes from it. */
  def indexStatus(node: NodeApi): Option[String] = {
    val c = node.indexedHeight().hcursor
    for {
      indexed <- c.downField("indexedHeight").as[Int].toOption
      full <- c.downField("fullHeight").as[Int].toOption
      if indexed < full - 1
    } yield s"warning: the node's index is at height $indexed of $full; results may be incomplete"
  }

  def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)
}

/** Positional arguments, `--key value` (or `--key=value`) options, and bare flags. */
case class Args(command: Option[String], positional: Seq[String], options: Map[String, Seq[String]], flags: Set[String]) {
  def opt(key: String): Option[String] = options.get(key).map(_.last)
  def opts(key: String): Seq[String] = options.getOrElse(key, Seq.empty)
  def flag(key: String): Boolean = flags.contains(key)
  def need(key: String): String = opt(key).getOrElse(throw CliError(s"missing --$key"))

  /** The shell's launch options fill in the node options a command line leaves out; --send never carries. */
  def withDefaults(launch: Args): Args =
    copy(options = launch.options.filter { case (k, _) => Args.SessionOptions.contains(k) } ++ options)
}

object Args {
  val Flags: Set[String] = Set("send", "mine", "liquidatable", "help", "old", "from-collateral", "dutch", "nav", "run",
    "deliver-shares", "exact-maturity", "mempool", "allow-liquidatable", "no-fill-guard")
  val SessionOptions: Set[String] = Set("node", "api-key", "wallet-pass", "address", "fee")
  val Options: Set[String] = SessionOptions ++ Set(
    "principal", "principal-token", "repayment", "collateral-erg", "collateral-token", "batching-fee",
    "liquidation", "maturity", "term", "duration",
    "market", "ratio", "oracle-age", "fill-ratio", "min-cover", "expires-in", "expiry-fee", "fee-reserve", "add-erg", "add-token",
    "shares", "share-name", "share-description", "decimals", "pull-fee", "mint-fee",
    "auction-opening", "auction-floor", "auction-length", "amount",
    "price", "margin", "min-fill", "refund-fee",
    "capital", "fees", "yield", "min-blocks", "max-blocks", "batcher-fee", "collateral-floor", "min-price",
    "min-auction-floor", "max-auction-length", "order", "ask")

  /** Splits a shell line on whitespace, keeping "double" or 'single' quoted text together. */
  def tokenize(line: String): Seq[String] = {
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var quote: Option[Char] = None
    var started = false
    line.foreach { ch =>
      quote match {
        case Some(q) if ch == q => quote = None
        case Some(_) => cur += ch
        case None if ch == '"' || ch == '\'' => quote = Some(ch); started = true
        case None if ch.isWhitespace => if (started || cur.nonEmpty) { out += cur.toString; cur.clear(); started = false }
        case None => cur += ch
      }
    }
    if (quote.isDefined) throw CliError("unterminated quote")
    if (started || cur.nonEmpty) out += cur.toString
    out.result()
  }

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
          } else throw CliError(s"unknown option --$key; run help for the list")
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
