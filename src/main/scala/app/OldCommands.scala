package ksingh.sigmabonds
package app

import ksingh.sigmabonds.app.SigmaFiCli.{indexStatus, one}
import scorex.util.encode.Base16

/**
 * The CLI's `--old` commands: the first-generation contracts in `contracts/old/`, kept for boxes already on
 * chain. New orders use the ZC Standard only, so there is no `create-order --old`.
 */
object OldCommands {

  def dispatch(app: SigmaFiApp, node: NodeApi, a: Args, hasApiKey: Boolean): Unit = a.command.get match {
    case "info" => info(app, node, hasApiKey)
    case "orders" => listOrders(app, node, a.flag("mine"))
    case "bonds" => listBonds(app, node, a.flag("mine"), a.flag("liquidatable"))
    case "claims" => listClaims(app, node)

    case "fill-order" => app.submit(app.fillOrder(one(a, "order box id"), SigmaFiCli.batchingFee(a)))
    case "claim" => app.submit(app.claim(one(a, "bond asset id")))

    case "create-order" =>
      throw CliError("new orders use the ZC Standard only: run create-order without --old")
    case "cancel-order" => app.submit(app.cancelOrder(one(a, "order box id")))
    case "repay" => app.submit(app.repay(one(a, "bond box id")))

    case "liquidate" => app.submit(app.liquidate(a.positional))

    case other => throw CliError(s"unknown first-generation command $other; run help for the list")
  }

  private def info(app: SigmaFiApp, node: NodeApi, hasApiKey: Boolean): Unit = {
    val p = app.protocol
    val network = app.networkType
    println(s"First-generation contracts. Network: $network; height ${app.height}")
    indexStatus(node).foreach(println)
    println(s"BondAssetVault:     ${p.vault.address(network)}")
    println(s"  hash (CONST_VAULT_HASH):   ${p.vault.hashedPropBytesHex}")
    println(s"ZC_ERG_Bond:        ${p.ergBond.address(network)}")
    println(s"  hash:             ${p.ergBond.hashedPropBytesHex}")
    println(s"ZC_Token_Bond template hash:  ${Base16.encode(p.tokenBondTemplateHash)}")
    println(s"ZC_Order template hash:       ${Base16.encode(p.orderTemplate.templateHash)}")
    println(s"ZC_OrderOnClose template hash: ${Base16.encode(p.onCloseTemplate.templateHash)}")
    p.liquidations.foreach(l => println(s"Liquidation ${l.name}: ${Base16.encode(l.hash)}"))
    if (hasApiKey) println(s"Wallet address: ${app.myAddress}")
  }

  private def listOrders(app: SigmaFiApp, node: NodeApi, mine: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    val orders = app.orders().filter(o => !mine || app.isWalletHash(o.borrowerHash))
    if (orders.isEmpty) println("No open first-generation orders.")
    orders.foreach { o =>
      val bond = app.protocol.pairedBond(o.bondHash, o.principalTokens)
      println(s"Order ${o.box.id}  " + (o.schedule match {
        case FixedMaturity(_) => "(first generation, fixed maturity)"
        case OnClose(_) => "(first generation, on-close)"
      }))
      println(s"  borrow:      ${Fmt.principal(o.principalErg, o.principalTokens)}; repay " +
        bond.fold("?")(t => Fmt.amount(o.repayment, t)))
      println("  maturity:    " + (o.schedule match {
        case FixedMaturity(h) => s"height $h (${h - app.height} blocks from now)"
        case OnClose(d) => s"$d blocks after the fill"
      }))
      println(s"  collateral:  ${Fmt.contents(o.box.value, o.box.tokens)}")
      println(s"  liquidation: ${liqName(app, o.liqScriptHash)}; fee reserve chosen and funded by lender on fill")
      if (bond.isEmpty) println("  WARNING: its bond contract does not match its principal; fill-order refuses it")
      if (app.isOwnHash(o.borrowerHash)) println("  (your order)")
    }
  }

  private def listBonds(app: SigmaFiApp, node: NodeApi, mine: Boolean, liquidatable: Boolean): Unit = {
    indexStatus(node).foreach(System.err.println)
    def matured(b: BondInfo) = app.height.toLong >= b.maturity
    val bonds = app.bonds()
      .filter(b => !liquidatable || matured(b) && app.protocol.liquidationOf(b.liqScriptHash).nonEmpty)
      .filter(b => !mine || app.isWalletHash(b.borrowerHash) || app.walletHolds(b.asset))
    if (bonds.isEmpty) println("No first-generation bonds.")
    bonds.foreach { b =>
      println(s"Bond ${b.box.id}  bond asset ${b.asset}  (first generation)")
      println(s"  repay:       ${Fmt.amount(b.repayment, b.repayToken)} " + (
        if (matured(b)) s"(matured at ${b.maturity}: liquidatable by ${liqName(app, b.liqScriptHash)})"
        else s"before height ${b.maturity} (${b.maturity - app.height} blocks left)"))
      println(s"  principal:   ${Fmt.principal(b.principalErg, b.principalTokens)}")
      println(s"  collateral:  ${Fmt.contents(b.collateralErg, b.collateral)}; batching fee ${Fmt.erg(b.batchingFee)}")
      if (app.isOwnHash(b.borrowerHash)) println("  (you are its borrower)")
      if (mine && app.walletHolds(b.asset)) println("  (you hold its bond asset)")
    }
  }

  private def listClaims(app: SigmaFiApp, node: NodeApi): Unit = {
    indexStatus(node).foreach(System.err.println)
    val claimable = app.vaultBoxes().filter { case (asset, _) => app.walletHolds(asset) }
    if (claimable.isEmpty) println("Nothing to claim from first-generation vaults.")
    claimable.foreach { case (asset, boxes) =>
      println(s"Bond asset $asset: ${Fmt.contents(boxes.map(_.value).sum, boxes.flatMap(_.tokens))} " +
        s"in ${boxes.size} vault box(es). Claim with: claim --old $asset")
    }
  }

  private def liqName(app: SigmaFiApp, hash: Array[Byte]): String =
    app.protocol.liquidationOf(hash).fold(s"UNKNOWN (${Base16.encode(hash)})")(_.name)
}
