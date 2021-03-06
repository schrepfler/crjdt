package eu.timepit.crjdt.core

import cats.instances.set._
import cats.syntax.order._
import eu.timepit.crjdt.core.Context.{ListCtx, MapCtx, RegCtx}
import eu.timepit.crjdt.core.Key.{IdK, StrK}
import eu.timepit.crjdt.core.ListRef.{HeadR, IdR, TailR}
import eu.timepit.crjdt.core.Mutation.{AssignM, DeleteM, InsertM}
import eu.timepit.crjdt.core.TypeTag.{ListT, MapT, RegT}
import eu.timepit.crjdt.core.Val.{EmptyList, EmptyMap}
import eu.timepit.crjdt.core.util.removeOrUpdate

import scala.annotation.tailrec

sealed trait Context extends Product with Serializable {
  final def next(cur: Cursor): Cursor =
    cur.view match {
      case Cursor.Leaf(k) =>
        val k1Ref = getNextRef(ListRef.fromKey(k))
        k1Ref match {
          case TailR => cur
          case keyRef: KeyRef =>
            val cur1 = Cursor.withFinalKey(keyRef.toKey)
            // NEXT2
            if (getPres(k).nonEmpty) cur1
            // NEXT3
            else next(cur1)
        }

      // NEXT4
      case Cursor.Branch(k1, cur1) =>
        findChild(k1).fold(cur) { child =>
          val cur2 = child.next(cur1)
          cur.copy(finalKey = cur2.finalKey)
        }
    }

  @tailrec
  final def keys(cur: Cursor): Set[String] =
    cur.view match {
      // KEYS2
      case Cursor.Leaf(k) =>
        findChild(MapT(k)) match {
          case Some(map: MapCtx) =>
            map.presSets.collect { case (StrK(key), v) if v.nonEmpty => key }.toSet
          case _ => Set.empty
        }

      // KEYS3
      case Cursor.Branch(k1, cur1) =>
        findChild(k1) match {
          case Some(child) => child.keys(cur1)
          case None => Set.empty
        }
    }

  @tailrec
  final def values(cur: Cursor): List[Val] =
    cur.view match {
      // VAL2
      case Cursor.Leaf(k) =>
        findChild(RegT(k)) match {
          case Some(reg: RegCtx) => reg.values.values.toList
          case _ => List.empty
        }

      // VAL3
      case Cursor.Branch(k1, cur1) =>
        findChild(k1) match {
          case Some(child) => child.values(cur1)
          case None => List.empty
        }
    }

  final def applyOp(op: Operation): Context =
    op.cur.view match {
      case Cursor.Leaf(k) =>
        op.mut match {
          // EMPTY-MAP, EMPTY-LIST
          case mut @ AssignM(EmptyMap | EmptyList) =>
            val tag = if (mut.value == EmptyMap) MapT(k) else ListT(k)
            val (ctx1, _) = clearElem(op.deps, k)
            val ctx2 = ctx1.addId(tag, op.id, op.mut)
            val child = ctx2.getChild(tag)
            ctx2.addCtx(tag, child)

          // ASSIGN
          case AssignM(value) =>
            val tag = RegT(k)
            val (ctx1, _) = clear(op.deps, tag)
            val ctx2 = ctx1.addId(tag, op.id, op.mut)
            val child = ctx2.getChild(tag)
            ctx2.addCtx(tag, child.addRegValue(op.id, value))

          case InsertM(value) =>
            val prevRef = ListRef.fromKey(k)
            val nextRef = getNextRef(prevRef)
            nextRef match {
              // INSERT2
              case IdR(nextId) if op.id < nextId =>
                applyOp(op.copy(cur = Cursor.withFinalKey(IdK(nextId))))

              // INSERT1
              case _ =>
                val idRef = IdR(op.id)
                val ctx1 = applyOp(
                  op.copy(cur = Cursor.withFinalKey(IdK(op.id)),
                          mut = AssignM(value)))
                ctx1.setNextRef(prevRef, idRef).setNextRef(idRef, nextRef)
            }

          // DELETE
          case DeleteM =>
            val (ctx1, _) = clearElem(op.deps, k)
            ctx1
        }

      // DESCEND
      case Cursor.Branch(k1, cur1) =>
        val child0 = getChild(k1)
        val child1 = child0.applyOp(op.copy(cur = cur1))
        val ctx1 = addId(k1, op.id, op.mut)
        ctx1.addCtx(k1, child1)
    }

  final def addCtx(tag: TypeTag, ctx: Context): Context =
    this match {
      case m: MapCtx => m.copy(entries = m.entries.updated(tag, ctx))
      case l: ListCtx => l.copy(entries = l.entries.updated(tag, ctx))
      case _: RegCtx => this
    }

  final def addRegValue(id: Id, value: Val): Context =
    this match {
      case r: RegCtx => r.copy(values = r.values.updated(id, value))
      case _ => this
    }

  final def getRegValues: RegValues =
    this match {
      case r: RegCtx => r.values
      case _ => Map.empty
    }

  final def addId(tag: TypeTag, id: Id, mut: Mutation): Context =
    mut match {
      // ADD-ID2
      case DeleteM => this
      // ADD-ID1
      case _ => setPres(tag.key, getPres(tag.key) + id)
    }

  final def clearElem(deps: Set[Id], key: Key): (Context, Set[Id]) = {
    val (ctx1, pres1) = clearAny(deps, key)
    val pres2 = ctx1.getPres(key)
    val pres3 = (pres1 ++ pres2) -- deps
    (ctx1.setPres(key, pres3), pres3)
  }

  // CLEAR-ANY
  final def clearAny(deps: Set[Id], key: Key): (Context, Set[Id]) = {
    val ctx0 = this
    val (ctx1, pres1) = ctx0.clear(deps, MapT(key))
    val (ctx2, pres2) = ctx1.clear(deps, ListT(key))
    val (ctx3, pres3) = ctx2.clear(deps, RegT(key))
    (ctx3, pres1 ++ pres2 ++ pres3)
  }

  final def clear(deps: Set[Id], tag: TypeTag): (Context, Set[Id]) =
    findChild(tag) match {
      // CLEAR-NONE
      case None => (this, Set.empty)
      case Some(child) =>
        tag match {
          // CLEAR-REG
          case tag: RegT =>
            val concurrent = child.getRegValues.filterKeys(id => !deps(id))
            (addCtx(tag, RegCtx(concurrent)), concurrent.keySet)

          // CLEAR-MAP1
          case tag: MapT =>
            val (cleared, pres) = child.clearMap(deps, Set.empty)
            (addCtx(tag, cleared), pres)

          // CLEAR-LIST1
          case tag: ListT =>
            val (cleared, pres) = child.clearList(deps, HeadR)
            (addCtx(tag, cleared), pres)
        }
    }

  // CLEAR-MAP2, CLEAR-MAP3
  final def clearMap(deps: Set[Id], done: Set[Key]): (Context, Set[Id]) = {
    val keys = keySet.map(_.key)
    (keys -- done).headOption.fold((this, Set.empty[Id])) { k =>
      val (ctx1, pres1) = clearElem(deps, k)
      val (ctx2, pres2) = ctx1.clearMap(deps, done + k)
      (ctx2, pres1 ++ pres2)
    }
  }

  final def clearList(deps: Set[Id], ref: ListRef): (Context, Set[Id]) =
    ref match {
      // CLEAR-LIST3
      case TailR => (this, Set.empty)
      // CLEAR-LIST2
      case keyRef: KeyRef =>
        val nextRef = getNextRef(ref)
        val (ctx1, pres1) = clearElem(deps, keyRef.toKey)
        val (ctx2, pres2) = ctx1.clearList(deps, nextRef)
        (ctx2, pres1 ++ pres2)
    }

  final def getChild(tag: TypeTag): Context =
    findChild(tag).getOrElse {
      tag match {
        // CHILD-MAP
        case _: MapT => Context.emptyMap
        // CHILD-LIST
        case _: ListT => Context.emptyList
        // CHILD-REG
        case _: RegT => Context.emptyReg
      }
    }

  // CHILD-GET
  final def findChild(tag: TypeTag): Option[Context] =
    this match {
      case m: MapCtx => m.entries.get(tag)
      case l: ListCtx => l.entries.get(tag)
      case _: RegCtx => None
    }

  // PRESENCE1, PRESENCE2
  final def getPres(key: Key): Set[Id] =
    this match {
      case m: MapCtx => m.presSets.getOrElse(key, Set.empty)
      case l: ListCtx => l.presSets.getOrElse(key, Set.empty)
      case _: RegCtx => Set.empty
    }

  final def setPres(key: Key, pres: Set[Id]): Context =
    this match {
      case m: MapCtx =>
        m.copy(presSets = removeOrUpdate(m.presSets, key, pres))
      case l: ListCtx =>
        l.copy(presSets = removeOrUpdate(l.presSets, key, pres))
      case _: RegCtx =>
        this
    }

  final def keySet: Set[TypeTag] =
    this match {
      case m: MapCtx => m.entries.keySet
      case _ => Set.empty
    }

  final def getNextRef(ref: ListRef): ListRef =
    this match {
      case l: ListCtx => l.order.getOrElse(ref, TailR)
      case _ => TailR
    }

  final def setNextRef(src: ListRef, dst: ListRef): Context =
    this match {
      case l: ListCtx => l.copy(order = l.order.updated(src, dst))
      case _ => this
    }
}

object Context {
  final case class MapCtx(entries: Map[TypeTag, Context],
                          presSets: Map[Key, Set[Id]])
      extends Context

  final case class ListCtx(entries: Map[TypeTag, Context],
                           presSets: Map[Key, Set[Id]],
                           order: Map[ListRef, ListRef])
      extends Context

  final case class RegCtx(values: RegValues) extends Context

  ///

  final def emptyMap: Context =
    MapCtx(entries = Map.empty, presSets = Map.empty)

  final def emptyList: Context =
    ListCtx(entries = Map.empty,
            presSets = Map.empty,
            order = Map(HeadR -> TailR))

  final def emptyReg: Context =
    RegCtx(values = Map.empty)
}
