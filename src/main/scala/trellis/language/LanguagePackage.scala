package trellis.language

import trellis.Core.*

/** A bidirectional concrete syntax implementation with an abstract tree representation. */
trait SyntaxCodec:
  type Tree
  def parse(source: String): Either[String, Tree]
  def print(tree: Tree): Either[String, String]

/** A replaceable language installed over the neutral graph repository. */
trait LanguagePackage:
  type Tree
  def id: String
  def version: String
  def syntax: SyntaxCodec { type Tree = LanguagePackage.this.Tree }
  def lower(tree: Tree, basis: Graph): Either[String, LoweredProgram]

final case class LoweredProgram(graph: Graph, root: EntityId, ir: String)
