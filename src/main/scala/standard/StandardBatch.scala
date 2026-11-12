package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.Parameters

import scala.collection.mutable
import scala.util.Try

/**
 * One piece of batchable work: what it does, the CLI command that does it, the nanoERG it pays whoever runs
 * it (before the miner fee), and the boxes it spends, so a batch run never spends a box twice.
 */
case class Work(summary: String, command: String, reward: Long, spends: Set[String])

/**
 * Everything in the standard set that waits on someone other than its owner: share mints and pulls, expiry
 * refunds, liquidations, and lend offers that some order or ask would satisfy. Each pays its runner a fee
 * the poster committed. Only work that pays more than the miner fee is listed.
 */
trait StandardBatch extends AppBase { self: StandardApp =>

  def batchable(): Seq[Work] = {
    val work = mutable.ArrayBuffer.empty[Work]
    val next = height.toLong + 1L
    def pays(fee: Long) = fee > Parameters.MinFee
    val oracleNow = Try(oracle()).toOption.flatten

    shareRequests().filter(r => pays(r.mintFee)).foreach { r =>
      work += Work(s"mint ${r.shares} shares of bond ${r.bondAsset}" + r.dutch.fold("")(_ => " (Dutch auction)"),
        s"mint-shares ${r.box.id}", r.mintFee, Set(r.box.id.toString))
    }
    val settled = settlements().map(s => s.asset -> s).toMap
    shareVaults().filter(v => v.holding && settled.contains(v.bondAsset) && pays(v.pullFee)).foreach { v =>
      work += Work(s"pull the settled bond ${v.bondAsset} into its share vault", s"pull ${v.box.id}", v.pullFee,
        Set(v.box.id.toString, settled(v.bondAsset).box.id.toString))
    }

    val liveBonds = self.bonds()
    liveBonds.foreach { b =>
      val rule = protocol.ruleOf(b.liqHash)
      val due = rule.isDefined && (next >= b.maturity || ((rule, oracleNow) match {
        case (Some(OracleRatio(_, _)), Some(o)) if b.payload.size >= 2 =>
          next - o.created <= b.payload(1) && protocol.cover(b, o.price).exists { case (c, d) => c * 10000 < d * b.payload(0) }
        case _ => false
      }))
      if (due && pays(b.feeReserve))
        work += Work(s"liquidate bond ${b.box.id} (${b.market})", s"liquidate ${b.box.id}", b.feeReserve, Set(b.box.id.toString))
    }

    val openOrders = orders()
    openOrders.filter(o => next >= o.expiry && pays(o.expiryFee)).foreach { o =>
      work += Work(s"return expired order ${o.box.id} to its borrower", s"refund-order ${o.box.id}", o.expiryFee, Set(o.box.id.toString))
    }
    val allOffers = offers()
    allOffers.filter(o => next >= o.expiry && pays(o.batcherFee)).foreach { o =>
      work += Work(s"return expired offer ${o.box.id} to its owner", s"refund-offer ${o.box.id}", o.batcherFee, Set(o.box.id.toString))
    }
    val openAsks = asks()
    openAsks.filter(a => next >= a.expiry && pays(a.box.value - Protocol.MinBox)).foreach { a =>
      work += Work(s"return expired ask ${a.box.id} to its seller", s"refund-ask ${a.box.id}", a.box.value - Protocol.MinBox,
        Set(a.box.id.toString))
    }

    // Matches: the first order or ask each live offer would take, judged by the offer's own checks.
    allOffers.filter(o => next < o.expiry && pays(o.batcherFee)).foreach { o =>
      val byOrder = openOrders.filter(order => order.market == o.market && next < order.expiry).find { order =>
        Try(checkOrder(order)).isSuccess && {
          val maturity = if (order.onClose) height.toLong + order.terms(3) else order.terms(3)
          val bond = StdBond(order.box, order.market, order.terms, order.principalTokens, order.borrowerHash,
            order.liqHash, o.feeReserve, height.toLong, maturity)
          offerRefusal(o, bond, order.principal, 1L, 1L, oracleNow).isEmpty
        }
      }.map(order => Work(s"fill order ${order.box.id} from offer ${o.box.id}", s"match-offer ${o.box.id} --order ${order.box.id}",
        o.batcherFee, Set(o.box.id.toString, order.box.id.toString)))
      val byAsk = byOrder.orElse(openAsks.filter(a => a.market == o.market && next < a.expiry).flatMap { a =>
        Try[Option[Work]] {
          val (asset, whole) = bondOf(a.token.id).get
          val units = if (whole == 1L) 1L else a.token.amount
          val bond = liveBonds.find(_.asset == asset).get
          val price = if (o.market.token.isEmpty) math.max(askPrice(a, units)._1, Protocol.MinBox) else askPrice(a, units)._1
          if (units < a.minFill && units != a.token.amount) None
          else if (offerRefusal(o, bond, price, units, whole, oracleNow).isEmpty)
            Some(Work(s"buy $units of ${a.token.id} from ask ${a.box.id} with offer ${o.box.id}",
              s"match-offer ${o.box.id} --ask ${a.box.id}" + (if (units != a.token.amount) s" --amount $units" else ""),
              o.batcherFee, Set(o.box.id.toString, a.box.id.toString)))
          else None
        }.toOption.flatten.toSeq
      }.headOption)
      byAsk.foreach(work += _)
    }
    work.result()
  }
}
