package org.lithosprotocol.sigmafi
package standard

import org.lithosprotocol.sigmafi.app.Protocol._
import org.lithosprotocol.sigmafi.app._
import org.ergoplatform.sdk.ErgoId
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/**
 * Custody wraps: ZC_Standard_WrapMint and ZC_Standard_WrapVault. A capability token, one whose presence among a
 * transaction's inputs grants authority elsewhere (a LithosDex provision's owner NFT), goes into a vault and comes
 * back only to whoever burns its receipt. Put the receipt, never the raw token, into orders, bonds and asks: those
 * let third parties spend the boxes holding it. Wrapping (anyone, for the mint fee) and refreshing run with the
 * wrap's own box as the transaction's only input, so the token is never an input beside anything else.
 */
trait StandardWraps extends AppBase { self: StandardApp =>

  import StandardProtocol._

  def wrapRequests(): Seq[StdWrapRequest] =
    decoded(nodeApi.unspentByErgoTree(protocol.contracts.wrapMint.ergoTreeHex)).flatMap(protocol.asWrapRequest)

  def wrapVaults(): Seq[StdWrapVault] =
    decoded(nodeApi.unspentByErgoTree(protocol.contracts.wrapVault.ergoTreeHex)).flatMap(protocol.asWrapVault)

  /** A receipt's vault: the one box holding 2 of it under the wrap vault script. */
  def wrapVault(receipt: ErgoId): Option[StdWrapVault] =
    decoded(nodeApi.unspentByTokenId(receipt.toString)).flatMap(protocol.asWrapVault).find(_.receiptId == receipt)

  /**
   * Whether a receipt is genuine: the box whose id it is was spent as a ZC_Standard_WrapMint request, which mints
   * exactly 3 and locks 2 in the vault. Off chain this needs one lookup; a contract checks the request's bytes.
   */
  def wrapVerified(receipt: ErgoId): Boolean =
    nodeApi.boxById(receipt.toString).exists { json =>
      NodeJson.spentBy(json).isDefined &&
        Try(NodeJson.box(json)).toOption.exists(b => sameTree(b.input.getErgoTree, protocol.contracts.wrapMint.ergoTree))
    }

  /**
   * Put `amount` of a token this wallet holds (all of it by default) into a wrap request. The request funds the
   * receipt's box, the vault with `refreshReserve` above its box minimum, and the mint fee; zcs1 metadata names the
   * receipt after the token.
   */
  def wrap(tokenId: String, amount: Option[Long], mintFee: Long, refreshReserve: Long): Prepared = {
    val id = Try(ErgoId.create(tokenId)).getOrElse(fail(s"$tokenId is not a token id"))
    val held = walletBoxes.flatMap(_.tokens).filter(_.id == id).map(_.amount).sum
    if (held == 0L) fail(s"this wallet holds no $tokenId")
    val n = amount.getOrElse(held)
    if (n < 1L || n > held) fail(s"wrap 1 to $held of $tokenId")
    if (mintFee < MinBatchingFee) fail(s"the mint fee must be at least ${Fmt.erg(MinBatchingFee)}")
    if (refreshReserve < 0L) fail("the refresh reserve cannot be negative")
    val metadata = StandardMetadata.receipt(id, n)
    val request = UTXO(protocol.contracts.wrapMint, addAmount(addAmount(2L * MinBox, refreshReserve), mintFee), Seq(Token(id, n)),
      Seq(bytesValue(hashOf(myTree)), longValue(mintFee), textsValue(metadata: _*)))
    prepare(s"Wrap $tokenId", plan(Seq.empty, Seq(request)), Seq(
      s"wraps $n of $tokenId; the receipt, named '${metadata.head}', comes to this wallet once a batcher mints it " +
        s"(`mint-wrap <request box id>`, for the ${Fmt.erg(mintFee)} mint fee)",
      s"the vault holds ${Fmt.erg(MinBox + refreshReserve)}: ${Fmt.erg(refreshReserve)} pays about " +
        s"${refreshReserve / RefreshBounty} refreshes, two years each, before storage rent could take it",
      "put the receipt, not the token, into orders, bonds and asks; `unwrap <receipt id>` takes the token back",
      "until it is minted, `cancel-wrap <request box id>` returns the token"))
  }

  /**
   * Mint a wrap request's receipt: 1 to the owner with the committed metadata, 2 and the token into the vault.
   * The request is the only input, so this wallet adds nothing: the mint fee pays the miner, and the rest is change.
   */
  def mintWrap(requestId: String): Prepared = {
    val req = protocol.asWrapRequest(unspent(requestId)).getOrElse(fail(s"$requestId is not a wrap request"))
    if (req.mintFee < MinBatchingFee || req.mintFee > req.box.value - 2L * MinBox)
      fail("the request's mint fee leaves too little for the receipt and vault boxes: only its owner can cancel it")
    val owner = ownerTree(req.ownerHash, req.box, "wrap owner")
    val receipt = UTXO(Contract(owner), MinBox, Seq(Token(req.receiptId, 1L)), req.metadata.map(bytesValue))
    val vault = UTXO(protocol.contracts.wrapVault, req.box.value - MinBox - req.mintFee,
      Seq(Token(req.receiptId, 2L), req.wrapped))
    val p = plan(Seq(req.box), Seq(receipt, vault), minted = Some(req.receiptId))
    if (p.inputs.size != 1) fail("the mint fee does not cover the miner fee, so the request cannot pay for its own mint")
    prepare(s"Mint wrap receipt $requestId", p,
      Seq(s"receipt ${req.receiptId} to the owner; ${req.wrapped.amount} of ${req.wrapped.id} into the vault",
        s"you keep the ${Fmt.erg(req.mintFee)} mint fee, less the miner fee"))
  }

  def cancelWrap(requestId: String): Prepared = {
    val req = protocol.asWrapRequest(unspent(requestId)).getOrElse(fail(s"$requestId is not a wrap request"))
    prepare(s"Cancel wrap request $requestId", sweep(Seq(req.box, walletBoxAt(req.ownerHash, "owner"))))
  }

  /** Burn the receipt with the vault's 2 and take the wrapped token back. */
  def unwrap(receiptId: String): Prepared = {
    val id = Try(ErgoId.create(receiptId)).getOrElse(fail(s"$receiptId is not a token id"))
    val v = wrapVault(id).getOrElse(fail(s"no wrap vault holds the reserve of $receiptId"))
    if (!wrapVerified(id)) fail(s"$receiptId was not minted by a standard wrap request: this vault proves nothing")
    val holder = walletBoxes.find(_.tokens.exists(_.id == id)).getOrElse(fail(s"this wallet does not hold receipt $receiptId"))
    val burned = v.box.tokens.head.amount + holder.tokens.filter(_.id == id).map(_.amount).sum
    prepare(s"Unwrap $receiptId", sweep(Seq(v.box, holder), burn = Seq(Token(id, burned))),
      Seq(s"burns all $burned units of the receipt and takes ${v.wrapped.amount} of ${v.wrapped.id} and the vault's " +
        Fmt.erg(v.box.value)))
  }

  /**
   * A wrap vault's paid refresh. The vault must be the only input, as for the mint: the bounty pays the miner and
   * the rest is change, so a vault left with too little to pay the miner fee waits for its owner to unwrap.
   */
  def wrapRefresh(v: StdWrapVault): Prepared = {
    val created = v.box.input.getCreationHeight.toLong
    if (!refreshable(created, height.toLong))
      fail(s"wrap vault ${v.box.id} was made at height $created; it can be refreshed from height ${created + RefreshAge + 1}")
    val value = refreshedValue(v.box.value, MinBox)
    val successor = UTXO(Contract(v.box.input.getErgoTree), value, v.box.tokens, Seq.empty, Some(height))
    val p = plan(Seq(v.box.withCtxVar(9.toByte, intValue(0))), Seq(successor))
    if (p.inputs.size != 1) fail(s"wrap vault ${v.box.id} holds too little to pay its own refresh, and nothing may be spent beside it")
    prepare(s"Refresh wrap vault ${v.box.id}", p,
      Seq(s"re-creates the vault unchanged, for a ${Fmt.erg(v.box.value - value)} bounty, less the miner fee"))
  }
}
