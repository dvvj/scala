/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author Gilles Dubochet
 * @author Martin Odersky
 */

package scala.tools.nsc
package transform

import symtab._
import Flags._
import scala.collection.{ mutable, immutable }
import scala.collection.mutable
import scala.tools.nsc.util.FreshNameCreator
import scala.runtime.ScalaRunTime.{ isAnyVal, isTuple }

/**
 * Perform Step 1 in the inline classes SIP
 *
 *  @author Martin Odersky
 *  @version 2.10
 */
abstract class ExtensionMethods extends Transform with TypingTransformers {

  import global._ // the global environment
  import definitions._ // standard classes and methods
  import typer.{ typed, atOwner } // methods to type trees

  /** the following two members override abstract members in Transform */
  val phaseName: String = "extmethods"

  def newTransformer(unit: CompilationUnit): Transformer =
    new Extender(unit)

  def hasExtension(sym: Symbol) =
    !sym.isParamAccessor && !sym.isConstructor

  /** Generate stream of possible names for the extension version of given instance method `imeth`.
   *  If the method is not overloaded, this stream consists of just "extension$imeth".
   *  If the method is overloaded, the stream has as first element "extensionX$imeth", where X is the
   *  index of imeth in the sequence of overloaded alternatives with the same name. This choice will
   *  always be picked as the name of the generated extension method.
   *  After this first choice, all other possible indices in the range of 0 until the number
   *  of overloaded alternatives are returned. The secondary choices are used to find a matching method
   *  in `extensionMethod` if the first name has the wrong type. We thereby gain a level of insensitivity
   *  of how overloaded types are ordered between phases and picklings.
   */
  private def extensionNames(imeth: Symbol): Stream[Name] =
    imeth.owner.info.decl(imeth.name).tpe match {
      case OverloadedType(_, alts) =>
        val index = alts indexOf imeth
        assert(index >= 0, alts+" does not contain "+imeth)
        def altName(index: Int) = newTermName("extension"+index+"$"+imeth.name)
        altName(index) #:: ((0 until alts.length).toStream filter (index !=) map altName)
      case tpe =>
        assert(tpe != NoType, imeth.name+" not found in "+imeth.owner+"'s decls: "+imeth.owner.info.decls)
        Stream(newTermName("extension$"+imeth.name))
    }

  /** Return the extension method that corresponds to given instance method `meth`.
   */
  def extensionMethod(imeth: Symbol): Symbol = atPhase(currentRun.refchecksPhase) {
    val companionInfo = imeth.owner.companionModule.info
    val candidates = extensionNames(imeth) map (companionInfo.decl(_))
    val matching = candidates filter (alt => normalize(alt.tpe, imeth.owner) matches imeth.tpe)
    assert(matching.nonEmpty, "no extension method found for "+imeth+" among "+candidates+"/"+extensionNames(imeth))
    matching.head
  }

   private def normalize(stpe: Type, clazz: Symbol): Type = stpe match {
    case PolyType(tparams, restpe) =>
      GenPolyType(tparams dropRight clazz.typeParams.length, normalize(restpe, clazz))
    case MethodType(tparams, restpe) =>
      restpe
    case _ =>
      stpe
  }

  class Extender(unit: CompilationUnit) extends TypingTransformer(unit) {

    private val extensionDefs = mutable.Map[Symbol, mutable.ListBuffer[Tree]]()

    def extensionMethInfo(extensionMeth: Symbol, origInfo: Type, clazz: Symbol): Type = {
      var newTypeParams = cloneSymbolsAtOwner(clazz.typeParams, extensionMeth)
      val thisParamType = appliedType(clazz.typeConstructor, newTypeParams map (_.tpe))
      val thisParam     = extensionMeth.newValueParameter(nme.SELF, extensionMeth.pos) setInfo thisParamType
      def transform(clonedType: Type): Type = clonedType match {
        case MethodType(params, restpe) =>
          // I assume it was a bug that this was dropping params...
          MethodType(thisParam :: params, clonedType)
        case NullaryMethodType(restpe) =>
          MethodType(List(thisParam), restpe)
      }
      val GenPolyType(tparams, restpe) = origInfo cloneInfo extensionMeth
      GenPolyType(tparams ::: newTypeParams, transform(restpe))
    }

    private def allParams(tpe: Type): List[Symbol] = tpe match {
      case MethodType(params, res) => params ::: allParams(res)
      case _ => List()
    }

    override def transform(tree: Tree): Tree = {
      tree match {
        case Template(_, _, _) =>
          if (currentOwner.isInlineClass) {
            extensionDefs(currentOwner.companionModule) = new mutable.ListBuffer[Tree]
            super.transform(tree)
          }
          else tree
        case DefDef(mods, name, tparams, vparamss, tpt, rhs)
        if currentOwner.isInlineClass && hasExtension(tree.symbol) =>
          val companion = currentOwner.companionModule
          val origMeth = tree.symbol
          val extensionName = extensionNames(origMeth).head
          val extensionMeth = companion.moduleClass.newMethod(extensionName, origMeth.pos, origMeth.flags & ~OVERRIDE | FINAL)
            .setAnnotations(origMeth.annotations)
          companion.info.decls.enter(extensionMeth)
          val newInfo = extensionMethInfo(extensionMeth, origMeth.info, currentOwner)
          extensionMeth setInfo newInfo
          log("Inline class %s spawns extension method.\n  Old: %s\n  New: %s".format(
            currentOwner,
            origMeth.defString, 
            extensionMeth.defString)) // extensionMeth.defStringSeenAs(origInfo

          def thisParamRef = gen.mkAttributedIdent(extensionMeth.info.params.head setPos extensionMeth.pos)
          val GenPolyType(extensionTpeParams, extensionMono) = extensionMeth.info
          val origTpeParams = origMeth.typeParams ::: currentOwner.typeParams
          val extensionBody = rhs
              .substTreeSyms(origTpeParams, extensionTpeParams)
              .substTreeSyms(vparamss.flatten map (_.symbol), allParams(extensionMono).tail)
              .substTreeThis(currentOwner, thisParamRef)
              .changeOwner((origMeth, extensionMeth))
          extensionDefs(companion) += atPos(tree.pos) { DefDef(extensionMeth, extensionBody) }
          val extensionCallPrefix = Apply(
              gen.mkTypeApply(gen.mkAttributedRef(companion), extensionMeth, origTpeParams map (_.tpe)),
              List(This(currentOwner)))
          val extensionCall = atOwner(origMeth) {
            localTyper.typed {
              atPos(rhs.pos) {
                (extensionCallPrefix /: vparamss) {
                  case (fn, params) => Apply(fn, params map (param => Ident(param.symbol)))
                }
              }
            }
          }
          treeCopy.DefDef(tree, mods, name, tparams, vparamss, tpt, extensionCall)
        case _ =>
          super.transform(tree)
      }
    }

    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] =
      super.transformStats(stats, exprOwner) map {
        case stat @ ModuleDef(mods, name, tmpl @ Template(parents, self, body)) =>
          extensionDefs.remove(stat.symbol) match {
            case Some(buf) =>
              val extensionDefs = buf.toList map { mdef => atOwner(stat.symbol) { localTyper.typed(mdef) }}
              treeCopy.ModuleDef(stat, mods, name, treeCopy.Template(tmpl, parents, self, body ++ buf))
            case None =>
              stat
          }
        case stat =>
          stat
      }
  }
}
