package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.ContractTemplate
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SInt, SLong, SType}
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

  property("no template carries the vault hash: the bond compiles it in, and CONST_BOND_HASH commits to it") {
    withCtx { ctx =>
      val vault = Colls.fromArray(ScriptGenerator.vaultHash(ctx))
      Seq(offerTemplate(ctx), orderTemplate(ctx), onCloseTemplate(ctx)).foreach { t =>
        t.tree.constants.map(_.value) should not contain vault
      }
    }
  }

  property("an offer minting 2 units has the same template hash, really mints 2, and does not match") {
    withCtx { ctx =>
      val t = offerTemplate(ctx)
      val c = closeScenario(ctx, ErgBond)
      val genuine = t.withTerms(c.terms)
      val forged = forge(genuine, indexOf(t, 1L, SLong), 2L, SLong)
      sameTemplate(forged, genuine) shouldBe true
      t.matches(forged) shouldBe false

      // On chain the forgery behaves as written: the borrower keeps a second claim on the bond
      val forgedIn = inputAt(c.offer.setContract(Contract(forged)), ctx, 0)
      val asset = forgedIn.id
      val second = UTXO(borrowerTree(ctx), minBox, Seq(Token(asset, 1L)))
      accepts(c.prover, closeTx(c)(
        inputs = Seq(forgedIn, c.borrowerIn),
        outputs = Seq(c.unitOut.setTokens(Token(asset, 1L)), bondUTXO(ctx, ErgBond, asset = asset),
          c.principalOut, second)))
    }
  }

  property("an order minting 2 units has the same template hash, really mints 2, and does not match") {
    withCtx { ctx =>
      val t = orderTemplate(ctx)
      val genuine = t.withTerms(orderTerms(ctx, ErgBond))
      val forged = forge(genuine, indexOf(t, 1L, SLong), 2L, SLong)
      sameTemplate(forged, genuine) shouldBe true
      t.matches(forged) shouldBe false

      // On chain the forgery behaves as written: a fill with a second unit goes through
      val f = fillScenario(ctx, ErgBond)
      val forgedIn = inputAt(f.order.setContract(Contract(forged)), ctx, 0)
      val asset = forgedIn.id
      val second = UTXO(lenderTree(ctx), minBox, Seq(Token(asset, 1L)))
      accepts(f.prover, fillTx(f)(
        inputs = Seq(forgedIn, f.lenderIn),
        outputs = Seq(f.unitOut.setTokens(Token(asset, 1L)), f.bondOut.withRegNum(4, bytesValue(asset.getBytes)),
          f.principalOut, second)))
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

  property("bonds and the vault have no terms: compare their whole trees, not templates") {
    withCtx { ctx =>
      // each has one address (a token bond, one per token); any forged constant changes the tree bytes
      Seq(bondContract(ctx, ErgBond), bondContract(ctx, TokenBond), vault(ctx)).foreach { c =>
        c.ergoTree.constants.indices.foreach { i =>
          val forged = c.ergoTree.constants(i).tpe match {
            case SLong => Some(forge(c.ergoTree, i, 123456789L, SLong))
            case SInt => Some(forge(c.ergoTree, i, 123456789, SInt))
            case SByteArray => Some(forge(c.ergoTree, i, Colls.fromArray(Array.fill(32)(7.toByte)), SByteArray))
            case _ => None
          }
          forged.foreach { f =>
            sameTemplate(f, c.ergoTree) shouldBe true
            f.bytes.toSeq should not equal c.ergoTree.bytes.toSeq
          }
        }
      }
      // in particular, a bond paying another vault is another address
      val vaultHash = Colls.fromArray(ScriptGenerator.vaultHash(ctx))
      val bond = bondContract(ctx, ErgBond).ergoTree
      val at = bond.constants.indexWhere(_.value == vaultHash)
      at should be >= 0
      forge(bond, at, Colls.fromArray(hashOf(otherTree(ctx))), SByteArray).bytes.toSeq should not equal bond.bytes.toSeq
    }
  }
}
