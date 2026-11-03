package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.ContractTemplate
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SLong, SType}
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * A template hash leaves out every constant, not only the terms. These pin down what that means for
 * verifying offers and orders, and that `ContractTemplate.matches` closes it.
 */
class TemplateVerificationSpec extends AnyPropSpec with BondSpecBase {

  import BondSpecBase._

  /** `tree` with one constant replaced, built the same way the client stamps instances. */
  private def forge(tree: ErgoTree, index: Int, value: Any, tpe: SType): ErgoTree =
    sigma.VersionContext.withVersions(3, 3) {
      ErgoTree(tree.header, tree.constants.updated(index, Constant[SType](value.asInstanceOf[SType#WrappedType], tpe)),
        tree.root.right.get)
    }

  private def sameTemplate(a: ErgoTree, b: ErgoTree): Boolean =
    Blake2b256.hash(a.template).toSeq == Blake2b256.hash(b.template).toSeq

  private def indexOf(t: ContractTemplate, value: Any, tpe: SType): Int = {
    val found = t.tree.constants.indices.filter(i => t.tree.constants(i).tpe == tpe && t.tree.constants(i).value == value)
    found.size shouldBe 1
    found.head
  }

  property("stamped offers and orders match their templates, whatever their terms") {
    withCtx { ctx =>
      Seq(
        offerTemplate(ctx) -> Seq(offerTerms(ctx, ErgBond), offerTerms(ctx, TokenBond, fee = 0L)),
        orderTemplate(ctx) -> Seq(orderTerms(ctx, ErgBond), orderTerms(ctx, TokenBond, fee = 0L)),
        onCloseTemplate(ctx) -> Seq(onCloseTerms(ctx, ErgBond), onCloseTerms(ctx, TokenBond, duration = 30))
      ).foreach { case (t, terms) =>
        terms.foreach(term => t.matches(t.withTerms(term)) shouldBe true)
      }
    }
  }

  property("every template pins the vault hash as a fixed constant, not a term") {
    withCtx { ctx =>
      val vault = Colls.fromArray(ScriptGenerator.vaultHash(ctx))
      Seq(offerTemplate(ctx), orderTemplate(ctx), onCloseTemplate(ctx)).foreach { t =>
        val i = indexOf(t, vault, SByteArray)
        t.positions.values.toSeq should not contain i
      }
    }
  }

  property("an offer paying another vault has the same template hash, and does not match") {
    withCtx { ctx =>
      val t = offerTemplate(ctx)
      val offer = t.withTerms(offerTerms(ctx, ErgBond))
      val forged = forge(offer, indexOf(t, Colls.fromArray(ScriptGenerator.vaultHash(ctx)), SByteArray),
        Colls.fromArray(hashOf(otherTree(ctx))), SByteArray)
      sameTemplate(forged, offer) shouldBe true
      t.matches(forged) shouldBe false
    }
  }

  property("an order minting 3 units has the same template hash, really mints 3, and does not match") {
    withCtx { ctx =>
      val t = orderTemplate(ctx)
      val genuine = t.withTerms(orderTerms(ctx, ErgBond))
      val forged = forge(genuine, indexOf(t, 2L, SLong), 3L, SLong)
      sameTemplate(forged, genuine) shouldBe true
      t.matches(forged) shouldBe false

      // On chain the forgery behaves as written: a fill with a third unit goes through
      val f = fillScenario(ctx, ErgBond)
      val forgedIn = inputAt(f.order.setContract(Contract(forged)), ctx, 0)
      val asset = forgedIn.id
      val third = UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)))
      accepts(f.prover, fillTx(f)(
        inputs = Seq(forgedIn, f.lenderIn),
        outputs = Seq(f.unitOut.setTokens(Token(asset, 1L)),
          f.bondOut.setTokens(Token(asset, 1L) +: defaultCollateral: _*).withRegNum(4, bytesValue(asset.getBytes)),
          f.principalOut, third)))
    }
  }

  property("a term may change in value but not in type") {
    withCtx { ctx =>
      val t = orderTemplate(ctx)
      val genuine = t.withTerms(orderTerms(ctx, ErgBond))
      val repayment = t.positions("CONST_REPAYMENT")
      t.matches(forge(genuine, repayment, 1L, SLong)) shouldBe true
      // a different type keeps the template bytes, since a placeholder only names an index
      t.matches(forge(genuine, repayment, Colls.fromArray(Array[Byte](1)), SByteArray)) shouldBe false
    }
  }

  property("the generator and bonds have no terms: compare their whole trees, not templates") {
    withCtx { ctx =>
      // each has one address; a forged literal changes the tree bytes, so an address check is enough
      Seq(generator(ctx), bondContract(ctx, ErgBond), vault(ctx)).foreach { c =>
        val i = c.ergoTree.constants.indexWhere(_.tpe == SLong)
        i should be >= 0
        forge(c.ergoTree, i, 123456789L, SLong).bytes.toSeq should not equal c.ergoTree.bytes.toSeq
      }
    }
  }
}
