package trellis

import java.nio.charset.StandardCharsets
import trellis.Core.*
import trellis.Delta.*
import trellis.storage.ProductCatalog

/**
 * Post-constitution language product line.
 *
 * LanguageBootstrap introduces graph-resident object languages, TemplateGrammar, and their
 * associated free change languages. ExpressionGrammar removes TemplateGrammar's embedded
 * production-template mini-language. GrammarCombinators adds first-class choice,
 * optional, repeat, precedence, and associativity. TypedCaptures makes AST capture
 * cardinality explicit graph data and gives constructor fields first-class sort
 * and cardinality identities. GraphLexer replaces host-side whitespace splitting
 * with a graph-resident deterministic lexer. LexicalExpressions adds first-class lexical
 * expression graphs and grammar-level token captures for identifiers, numbers,
 * and quoted strings. LexicalCodecs adds graph-resident bidirectional lexical codecs,
 * so token source text is decoded to semantic values and canonically re-encoded
 * without hiding value normalization in the host. SourceProvenance adds lossless source
 * provenance: raw token spans and trivia are preserved independently of semantic
 * normalization. SourceMapLayout adds semantic AST source maps and graph-defined offside
 * layout events (NEWLINE/INDENT/DEDENT) over the preserved source substrate.
 * VirtualLayout makes those virtual layout tokens first-class grammar expressions and
 * consumes a graph-defined lexical/layout token stream. StatementSuites adds explicit
 * statement forms and a recursive suite combinator, so nested offside blocks own
 * their line termination structurally rather than through parser preference.
 * TypedHoleDiagnostics adds explicit typed holes plus graph-selected bounded parse recovery
 * and source diagnostics, while strict parsing remains fail-closed. ModuleNameResolution
 * adds graph-resident modules, namespaces, imports, and deterministic name
 * resolution. LambdaMatchSemantics begins the reference language semantics with graph-
 * defined lambda, apply, construct, and match forms. AsyncProcesses adds language-
 * owned process programs and asynchronous FIFO channel semantics over the
 * graph-dispatched reference machine. SessionProtocols adds deterministic dual session
 * protocols over send/receive labels. The frozen F0-F11 constitution remains
 * untouched.
 */
object Language:

  enum Cardinality:
    case One, Optional, Many

  enum FieldValue:
    case One(tree: Tree)
    case Optional(value: Option[Tree])
    case Many(values: Vector[Tree])
    case Lexeme(sort: String, constructor: EntityId, text: String)
    case Value(sort: String, constructor: EntityId, value: String)

  final case class Tree(
      sort: String,
      constructor: EntityId,
      fields: Vector[(String, Tree)] = Vector.empty,
      cardinalFields: Vector[(String, FieldValue)] = Vector.empty
  ):
    def fieldValue(name: String): Option[FieldValue] =
      fields.collectFirst { case (`name`, tree) => FieldValue.One(tree) }
        .orElse(cardinalFields.collectFirst { case (`name`, value) => value })

    def fieldValues: Vector[(String, FieldValue)] =
      fields.map { case (name, tree) => name -> FieldValue.One(tree) } ++ cardinalFields

  final case class FieldSpec(name: String, sort: String, cardinality: Cardinality)

  final case class LanguageSpec(
      entity: EntityId,
      name: String,
      startSort: EntityId,
      grammar: EntityId,
      changes: EntityId
  )

  final case class ConstructorSpec(
      entity: EntityId,
      name: String,
      sort: String,
      fields: Vector[FieldSpec]
  )

  enum PatternItem:
    case Literal(value: String)
    case Field(name: String, sort: String)

  enum GrammarExpr:
    case Literal(value: String)
    case Reference(field: String, sort: String)
    case Sequence(items: Vector[GrammarExpr])
    case Choice(alternatives: Vector[GrammarExpr], printAlternative: Int)
    case Optional(body: GrammarExpr, printIncluded: Boolean)
    case Repeat(body: GrammarExpr, min: Int, max: Int, printCount: Int)
    case Capture(field: String, sort: String, cardinality: Cardinality, body: GrammarExpr, min: Int, max: Int)
    case Token(field: String, sort: String, rule: EntityId, constructor: EntityId)
    case LayoutToken(token: EntityId)
    case Suite(field: String, sort: String, min: Int, max: Int, newline: EntityId, indent: EntityId, dedent: EntityId)
    case Hole(surface: String, sort: String)

  enum LexerExpr:
    case Literal(value: String)
    case CharClass(primitive: String)
    case Sequence(items: Vector[LexerExpr])
    case Choice(alternatives: Vector[LexerExpr])
    case Repeat(body: LexerExpr, min: Int, max: Int)

  final case class EscapeSpec(entity: EntityId, source: String, value: String)

  enum LexerCodec:
    case Identity(codecEntity: EntityId)
    case DecimalNatural(codecEntity: EntityId, radix: Int, stripLeadingZeros: Boolean, zero: String)
    case QuotedString(codecEntity: EntityId, quote: String, escape: String, mappings: Vector[EscapeSpec])

    def entity: EntityId = this match
      case Identity(entity) => entity
      case DecimalNatural(entity, _, _, _) => entity
      case QuotedString(entity, _, _, _) => entity

    def kind: String = this match
      case Identity(_) => "identity"
      case DecimalNatural(_, _, _, _) => "decimal-natural"
      case QuotedString(_, _, _, _) => "quoted-string"

  final case class LexerRule(
      entity: EntityId,
      name: String,
      priority: Int,
      sort: String,
      constructor: EntityId,
      body: LexerExpr,
      codec: Option[LexerCodec] = None
  )

  final case class LexToken(
      text: String,
      rule: Option[EntityId],
      value: Option[String] = None,
      layout: Option[EntityId] = None
  )

  final case class SourceSpan(startByte: Int, endByte: Int):
    require(startByte >= 0, "source span start must be non-negative")
    require(endByte >= startByte, "source span end precedes start")

  final case class Trivia(kind: String, text: String, span: SourceSpan)

  final case class SourceToken(token: LexToken, span: SourceSpan, leadingTrivia: Vector[Trivia])

  final case class SourceDocument(
      source: String,
      tokens: Vector[SourceToken],
      trailingTrivia: Vector[Trivia]
  ):
    def renderLossless: String =
      tokens.flatMap(token => token.leadingTrivia.map(_.text) :+ token.token.text).mkString + trailingTrivia.map(_.text).mkString

  final case class SourcePolicy(
      entity: EntityId,
      offsetUnit: String,
      triviaMode: String,
      attachment: String,
      reconstruction: String
  )

  final case class SourceMapPolicy(
      entity: EntityId,
      offsetUnit: String,
      nodeSpan: String,
      path: String,
      failure: String
  )

  final case class LayoutPolicy(
      entity: EntityId,
      mode: String,
      indentUnit: Int,
      indentation: String,
      tabs: String,
      blankLines: String,
      commentLines: String,
      rootIndent: Int,
      eofDedent: Boolean,
      failure: String,
      newlineToken: EntityId,
      indentToken: EntityId,
      dedentToken: EntityId,
      consumption: String,
      streamOrder: String,
      virtualWidth: String
  )

  final case class LayoutEvent(token: EntityId, atByte: Int, depth: Int)

  final case class LayoutDocument(source: SourceDocument, events: Vector[LayoutEvent])

  final case class AstSourceEntry(path: String, constructor: EntityId, span: SourceSpan)

  final case class AstSourceMap(entries: Vector[AstSourceEntry]):
    def root: Option[AstSourceEntry] = entries.find(_.path == "$")
    def at(path: String): Option[AstSourceEntry] = entries.find(_.path == path)

  final case class ParsedSource(tree: Tree, document: SourceDocument, sourceMap: AstSourceMap)

  final case class HoleSpec(entity: EntityId, surface: String, sort: String)

  final case class RecoveryPolicy(
      entity: EntityId,
      strategies: Vector[EntityId],
      hole: HoleSpec,
      maxEdits: Int,
      selection: String,
      candidateBoundaries: String,
      failure: String
  )

  final case class DiagnosticPolicy(
      entity: EntityId,
      severity: EntityId,
      insertedHoleCode: EntityId,
      offsetUnit: String,
      expected: String
  )

  final case class RecoveryEdit(strategy: EntityId, span: SourceSpan, inserted: String)

  final case class ParseDiagnostic(
      code: EntityId,
      severity: EntityId,
      message: String,
      span: SourceSpan,
      expected: Vector[String]
  )

  final case class RecoveredParse(tree: Tree, diagnostics: Vector[ParseDiagnostic], edits: Vector[RecoveryEdit])

  final case class ModuleSpec(entity: EntityId, language: EntityId, name: String)

  final case class NameBinding(
      entity: EntityId,
      module: EntityId,
      name: String,
      namespace: String,
      target: EntityId,
      visibility: String
    )

  final case class ModuleImport(
      entity: EntityId,
      module: EntityId,
      imported: EntityId,
      alias: Option[String],
      mode: String
    )

  final case class NamePolicy(
      entity: EntityId,
      language: EntityId,
      qualification: String,
      shadowing: String,
      ambiguity: String,
      visibility: String,
      cycles: String
    )

  final case class ResolvedName(reference: String, binding: NameBinding, path: Vector[EntityId])

  final case class EvaluationPolicy(
      entity: EntityId,
      language: EntityId,
      strategy: String,
      scope: String,
      matching: String,
      failure: String,
      maxSteps: Int
  )

  final case class MatchCase(entity: EntityId, constructor: EntityId, binder: Option[String], body: EntityId)

  enum SemanticValue:
    case Data(constructor: EntityId, fields: Vector[(String, SemanticValue)])
    case Closure(parameter: String, body: EntityId, environment: Map[String, SemanticValue])

  final case class EvaluationResult(value: SemanticValue, steps: Int)

  final case class ProcessPolicy(
      entity: EntityId,
      language: EntityId,
      scheduling: String,
      channelOrder: String,
      send: String,
      receive: String,
      failure: String,
      maxSteps: Int
  )

  final case class ProcessResult(state: Machine.State, steps: Int)
  final case class DifferentialCertificate(program: EntityId, instructions: Int, netRoot: ContentId, ceskrRoot: ContentId, deltaNetRoot: ContentId)

  final case class SessionPolicy(
      entity: EntityId,
      language: EntityId,
      determinism: String,
      duality: String,
      completion: String,
      failure: String,
      maxTransitions: Int
  )

  final case class SessionAction(direction: String, label: String)

  final case class SessionResult(protocol: EntityId, finalState: EntityId, transitions: Vector[EntityId])

  final case class CeskrPolicy(entity: EntityId, language: EntityId, selection: String, order: String, resources: String, failure: String, maxTransitions: Int)
  final case class CeskrRule(entity: EntityId, form: String, action: String)
  final case class CeskrTransition(index: Int, rule: EntityId, control: EntityId, action: String)
  final case class CeskrResult(value: SemanticValue, transitions: Vector[CeskrTransition])
  final case class TracePolicy(entity: EntityId, language: EntityId, format: String, offsets: String, identity: String, failure: String)
  final case class CeskrTrace(expression: EntityId, valueConstructor: EntityId, transitions: Vector[CeskrTransition])

  private enum PrintPiece:
    case Text(value: String)
    case Layout(token: EntityId)

  final case class Production(
      entity: EntityId,
      sort: String,
      constructor: EntityId,
      pattern: Vector[PatternItem],
      body: Option[GrammarExpr],
      priority: Int,
      fixity: String,
      precedence: Int,
      associativity: String,
      statementForm: String = "simple"
  )

  final case class ChangeLanguageSpec(
      entity: EntityId,
      language: EntityId,
      basis: String,
      free: Boolean,
      allowedOps: Set[String]
  )

  final case class ChangeOperator(
      entity: EntityId,
      primitive: String,
      targetKinds: Set[String]
  )

  final case class LexerSpec(
      entity: EntityId,
      source: String,
      whitespace: String,
      lineComment: Option[String],
      selection: String,
      tieBreak: String,
      boundary: String,
      failure: String
  )

  lazy val graph: Graph = ProductCatalog.graphIntroducing(EntityId("optimization.differential.policy"))

  def languages(graph: Graph): Vector[LanguageSpec] =
    graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(_.kind == "language.definition").flatMap { _ =>
        definition(graph, entity).toOption
      }
    }

  def definition(graph: Graph, language: EntityId): Either[String, LanguageSpec] =
    graph.entity(language) match
      case Some(node) if node.kind == "language.definition" =>
        for
          name <- required(node, "name", language.value)
          start <- sourceEntityAt(graph, language, "start")
          grammar <- sourceEntityAt(graph, language, "grammar")
          changes <- sourceEntityAt(graph, language, "changes")
          _ <- expectKind(graph, start, "language.sort")
          _ <- expectKind(graph, grammar, "grammar.definition")
          _ <- expectKind(graph, changes, "language.change-language")
        yield LanguageSpec(language, name, start, grammar, changes)
      case Some(node) => Left(s"${language.value} is ${node.kind}, not language.definition")
      case None => Left(s"unknown language ${language.value}")

  def constructors(graph: Graph, language: EntityId): Vector[ConstructorSpec] =
    graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId)
        .filter(node => node.kind == "language.constructor" && node.attrs.get("language").contains(language.value))
        .flatMap(node => decodeConstructor(graph, entity, node).toOption)
    }

  def productions(graph: Graph, language: EntityId): Either[String, Vector[Production]] =
    definition(graph, language).flatMap { spec =>
      for
        encoding <- grammarEncoding(graph, spec)
        grammarId <- graph.entities.get(spec.grammar).toRight(s"missing grammar ${spec.grammar.value}")
        grammarNode <- graph.nodes.get(grammarId).toRight(s"missing grammar node ${grammarId.value}")
        ports = grammarNode.ports.filter(p => p.direction == Direction.In && p.name.startsWith("p")).sortBy(_.name)
        decoded <- sequence(ports.map { p =>
          for
            prodEntity <- sourceEntityAt(graph, spec.grammar, p.name)
            prodNode <- graph.entity(prodEntity).toRight(s"missing production ${prodEntity.value}")
            _ <- if prodNode.kind == "grammar.production" then Right(()) else Left(s"${prodEntity.value} is ${prodNode.kind}, not grammar.production")
            sort <- required(prodNode, "sort", prodEntity.value)
            priority <- prodNode.attrs.get("priority").flatMap(_.toIntOption).toRight(s"${prodEntity.value} lacks integer priority")
            ctor <- sourceEntityAt(graph, prodEntity, "constructor")
            decodedBody <- encoding match
              case "template" =>
                required(prodNode, "pattern", prodEntity.value).flatMap(decodePattern).map(pattern => (pattern, None, "prefix", 0, "none"))
              case "expression-graph" =>
                for
                  bodyEntity <- sourceEntityAt(graph, prodEntity, "body")
                  body <- decodeGrammarExpr(graph, language, bodyEntity, Set.empty)
                  pattern <- flattenSimpleExpression(body)
                yield (pattern, Some(body), "prefix", 0, "none")
              case "combinator-graph" | "capture-graph" | "lexer-graph" | "lexical-graph" | "lexical-codec-graph" | "lexical-source-graph" | "lexical-source-layout-graph" | "layout-grammar-graph" | "suite-grammar-graph" | "recovery-grammar-graph" =>
                for
                  bodyEntity <- sourceEntityAt(graph, prodEntity, "body")
                  body <- decodeGrammarExpr(graph, language, bodyEntity, Set.empty)
                  fixity <- required(prodNode, "fixity", prodEntity.value)
                  precedence <- productionPrecedence(graph, prodEntity)
                  associativity <- productionAssociativity(graph, prodEntity)
                yield (Vector.empty[PatternItem], Some(body), fixity, precedence, associativity)
              case other => Left(s"unsupported grammar encoding $other")
            (pattern, body, fixity, precedence, associativity) = decodedBody
            statementForm <- if Set("suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then productionStatementForm(graph, prodEntity) else Right("simple")
          yield Production(prodEntity, sort, ctor, pattern, body, priority, fixity, precedence, associativity, statementForm)
        })
      yield decoded.sortBy(p => (p.priority, p.entity.value))
    }

  def lexer(graph: Graph, language: EntityId): Either[String, LexerSpec] =
    definition(graph, language).flatMap { spec =>
      grammarEncoding(graph, spec).flatMap { encoding =>
        if Set("lexer-graph", "lexical-graph", "lexical-codec-graph", "lexical-source-graph", "lexical-source-layout-graph", "layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then lexerDefinition(graph, spec)
        else Left(s"${spec.grammar.value} does not select a graph-resident lexer")
      }
    }

  def lexerRules(graph: Graph, language: EntityId): Either[String, Vector[LexerRule]] =
    definition(graph, language).flatMap { spec =>
      grammarEncoding(graph, spec).flatMap {
        case "lexical-graph" | "lexical-codec-graph" | "lexical-source-graph" | "lexical-source-layout-graph" | "layout-grammar-graph" | "suite-grammar-graph" | "recovery-grammar-graph" => lexerRuleDefinitions(graph, spec)
        case _ => Right(Vector.empty)
      }
    }

  def sourcePolicy(graph: Graph, language: EntityId): Either[String, SourcePolicy] =
    definition(graph, language).flatMap { spec => sourcePolicyDefinition(graph, spec) }

  def sourceMapPolicy(graph: Graph, language: EntityId): Either[String, SourceMapPolicy] =
    definition(graph, language).flatMap(spec => sourceMapPolicyDefinition(graph, spec))

  def layoutPolicy(graph: Graph, language: EntityId): Either[String, LayoutPolicy] =
    definition(graph, language).flatMap(spec => layoutPolicyDefinition(graph, spec))

  def recoveryPolicy(graph: Graph, language: EntityId): Either[String, RecoveryPolicy] =
    definition(graph, language).flatMap(spec => recoveryPolicyDefinition(graph, spec))

  def diagnosticPolicy(graph: Graph, language: EntityId): Either[String, DiagnosticPolicy] =
    definition(graph, language).flatMap(spec => diagnosticPolicyDefinition(graph, spec))

  def modules(graph: Graph, language: EntityId): Either[String, Vector[ModuleSpec]] =
    val decoded = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(_.kind == "language.module").map(node => decodeModule(entity, node))
    }
    sequence(decoded).flatMap { values =>
      val selected = values.filter(_.language == language)
      selected.groupBy(_.name).collectFirst { case (name, duplicates) if duplicates.size > 1 => name } match
        case Some(name) => Left(s"duplicate module name $name in ${language.value}")
        case None => Right(selected)
    }

  def namePolicy(graph: Graph, language: EntityId): Either[String, NamePolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.name-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          qualification <- required(node, "qualification", entity.value)
          shadowing <- required(node, "shadowing", entity.value)
          ambiguity <- required(node, "ambiguity", entity.value)
          visibility <- required(node, "visibility", entity.value)
          cycles <- required(node, "cycles", entity.value)
          _ <- if qualification == "dot" && shadowing == "local-over-import" && ambiguity == "reject" && visibility == "public-across-modules" && cycles == "reject" then Right(()) else Left(s"unsupported name policy ${entity.value}")
        yield NamePolicy(entity, language, qualification, shadowing, ambiguity, visibility, cycles)
      case Vector() => Left(s"missing name policy for ${language.value}")
      case _ => Left(s"multiple name policies for ${language.value}")

  def resolveName(
      graph: Graph,
      language: EntityId,
      fromModule: EntityId,
      reference: String,
      namespace: String = "value"
  ): Either[String, ResolvedName] =
    for
      _ <- namePolicy(graph, language)
      knownModules <- modules(graph, language)
      moduleById = knownModules.map(module => module.entity -> module).toMap
      _ <- moduleById.get(fromModule).toRight(s"unknown module ${fromModule.value}")
      bindings <- moduleBindings(graph, moduleById.keySet)
      imports <- moduleImports(graph, moduleById.keySet)
      parts = reference.split('.').toVector.filter(_.nonEmpty)
      _ <- if parts.nonEmpty && parts.mkString(".") == reference then Right(()) else Left(s"invalid name reference $reference")
      resolved <-
        if parts.size == 1 then resolveUnqualified(fromModule, parts.head, namespace, bindings, imports, Set.empty)
        else
          val qualifier = parts.head
          val name = parts.tail.mkString(".")
          imports.filter(item => item.module == fromModule && item.alias.contains(qualifier)) match
            case Vector(item) => resolveExported(item.imported, name, namespace, bindings, imports, Set(fromModule)).map(binding => ResolvedName(reference, binding, Vector(fromModule, item.imported)))
            case Vector() => Left(s"unknown module qualifier $qualifier in ${fromModule.value}")
            case _ => Left(s"ambiguous module qualifier $qualifier in ${fromModule.value}")
    yield resolved

  def evaluationPolicy(graph: Graph, language: EntityId): Either[String, EvaluationPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.evaluation-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          strategy <- required(node, "strategy", entity.value)
          scope <- required(node, "scope", entity.value)
          matching <- required(node, "matching", entity.value)
          failure <- required(node, "failure", entity.value)
          maxSteps <- node.attrs.get("max-steps").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-steps")
          _ <- if strategy == "call-by-value" && scope == "lexical" && matching == "first-constructor" && failure == "strict" && maxSteps > 0 && maxSteps <= 4096 then Right(()) else Left(s"unsupported evaluation policy ${entity.value}")
        yield EvaluationPolicy(entity, language, strategy, scope, matching, failure, maxSteps)
      case Vector() => Left(s"missing evaluation policy for ${language.value}")
      case _ => Left(s"multiple evaluation policies for ${language.value}")

  def evaluate(graph: Graph, language: EntityId, expression: EntityId): Either[String, EvaluationResult] =
    evaluationPolicy(graph, language).flatMap { policy =>
      var steps = 0

      def tick(entity: EntityId): Either[String, Node] =
        steps += 1
        if steps > policy.maxSteps then Left(s"evaluation exceeded ${policy.maxSteps} steps")
        else graph.entity(entity).toRight(s"unknown semantic expression ${entity.value}")

      def owned(entity: EntityId, node: Node): Either[String, Unit] =
        if node.attrs.get("language").contains(language.value) then Right(())
        else Left(s"${entity.value} is not owned by ${language.value}")

      def expressionFields(entity: EntityId, node: Node): Either[String, Vector[(String, EntityId)]] =
        val names = node.attrs.toVector.collect { case (key, value) if key.startsWith("field") && key.endsWith("-name") => key.stripSuffix("-name") -> value }.sortBy(_._1)
        val expected = (1 to names.size).map(index => f"field$index%02d").toVector
        if names.map(_._1) != expected then Left(s"${entity.value} has non-canonical constructor fields")
        else sequence(names.map { case (prefix, name) =>
          node.attrs.get(s"$prefix-value").filter(_.nonEmpty).toRight(s"${entity.value} lacks $prefix-value").map(value => name -> EntityId(value))
        })

      def matchCases(entity: EntityId, node: Node): Either[String, Vector[MatchCase]] =
        val entries = node.attrs.toVector.collect { case (key, value) if key.startsWith("case") => key -> EntityId(value) }.sortBy(_._1)
        val expected = (1 to entries.size).map(index => f"case$index%02d").toVector
        if entries.isEmpty then Left(s"${entity.value} has no match cases")
        else if entries.map(_._1) != expected then Left(s"${entity.value} has non-canonical match cases")
        else sequence(entries.map { case (_, caseEntity) =>
          graph.entity(caseEntity) match
            case Some(caseNode) if caseNode.kind == "language.match-case" =>
              for
                _ <- owned(caseEntity, caseNode)
                constructor <- required(caseNode, "constructor", caseEntity.value).map(EntityId.apply)
                _ <- graph.entity(constructor).filter(_.kind == "language.constructor").toRight(s"${caseEntity.value} names invalid constructor ${constructor.value}")
                body <- required(caseNode, "body", caseEntity.value).map(EntityId.apply)
                _ <- graph.entity(body).toRight(s"${caseEntity.value} names unknown body ${body.value}")
              yield MatchCase(caseEntity, constructor, caseNode.attrs.get("binder").filter(_.nonEmpty), body)
            case Some(caseNode) => Left(s"${caseEntity.value} is ${caseNode.kind}, not language.match-case")
            case None => Left(s"unknown match case ${caseEntity.value}")
        })

      def loop(entity: EntityId, environment: Map[String, SemanticValue]): Either[String, SemanticValue] =
        tick(entity).flatMap { node =>
          owned(entity, node).flatMap { _ =>
            node.kind match
              case "language.expr.reference" =>
                required(node, "name", entity.value).flatMap(name => environment.get(name).toRight(s"unbound semantic name $name"))
              case "language.expr.lambda" =>
                for
                  parameter <- required(node, "parameter", entity.value)
                  body <- required(node, "body", entity.value).map(EntityId.apply)
                  _ <- graph.entity(body).toRight(s"${entity.value} names unknown body ${body.value}")
                yield SemanticValue.Closure(parameter, body, environment)
              case "language.expr.apply" =>
                for
                  function <- required(node, "function", entity.value).map(EntityId.apply)
                  argument <- required(node, "argument", entity.value).map(EntityId.apply)
                  callable <- loop(function, environment)
                  value <- loop(argument, environment)
                  result <- callable match
                    case SemanticValue.Closure(parameter, body, closureEnvironment) => loop(body, closureEnvironment.updated(parameter, value))
                    case _ => Left(s"${entity.value} attempted to apply a non-closure")
                yield result
              case "language.expr.construct" =>
                for
                  constructor <- required(node, "constructor", entity.value).map(EntityId.apply)
                  _ <- graph.entity(constructor).filter(_.kind == "language.constructor").toRight(s"${entity.value} names invalid constructor ${constructor.value}")
                  fields <- expressionFields(entity, node)
                  values <- sequence(fields.map { case (name, valueEntity) => loop(valueEntity, environment).map(name -> _) })
                yield SemanticValue.Data(constructor, values)
              case "language.expr.match" =>
                for
                  scrutinee <- required(node, "scrutinee", entity.value).map(EntityId.apply)
                  value <- loop(scrutinee, environment)
                  data <- value match
                    case item: SemanticValue.Data => Right(item)
                    case _ => Left(s"${entity.value} attempted to match a closure")
                  cases <- matchCases(entity, node)
                  selected <- cases.find(_.constructor == data.constructor).toRight(s"non-exhaustive match for ${data.constructor.value}")
                  nextEnvironment = selected.binder.fold(environment)(name => environment.updated(name, data))
                  result <- loop(selected.body, nextEnvironment)
                yield result
              case other => Left(s"unsupported semantic expression kind $other at ${entity.value}")
          }
        }

      loop(expression, Map.empty).map(value => EvaluationResult(value, steps))
    }

  def processPolicy(graph: Graph, language: EntityId): Either[String, ProcessPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.process-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          scheduling <- required(node, "scheduling", entity.value)
          channelOrder <- required(node, "channel-order", entity.value)
          send <- required(node, "send", entity.value)
          receive <- required(node, "receive", entity.value)
          failure <- required(node, "failure", entity.value)
          maxSteps <- node.attrs.get("max-steps").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-steps")
          _ <- if scheduling == "program-order" && channelOrder == "fifo" && send == "asynchronous" && receive == "block-or-deliver" && failure == "strict" && maxSteps > 0 && maxSteps <= 4096 then Right(()) else Left(s"unsupported process policy ${entity.value}")
        yield ProcessPolicy(entity, language, scheduling, channelOrder, send, receive, failure, maxSteps)
      case Vector() => Left(s"missing process policy for ${language.value}")
      case _ => Left(s"multiple process policies for ${language.value}")

  def runProcessProgram(graph: Graph, language: EntityId, program: EntityId): Either[String, ProcessResult] =
    for
      instructions <- processProgramInstructions(graph, language, program)
      state <- Machine.run(instructions, graph = graph)
    yield ProcessResult(state, instructions.size)

  def differentialProcessProgram(graph: Graph, language: EntityId, program: EntityId): Either[String, DifferentialCertificate] =
    for
      _ <- differentialPolicy(graph)
      _ <- differentialWitness(graph, program)
      instructions <- processProgramInstructions(graph, language, program)
      net <- Machine.DeltaNet.lower(instructions, graph)
      ceskr <- Machine.run(instructions, graph = graph)
      deltaNet <- Machine.DeltaNet.runIndependent(instructions, graph = graph)
      ceskrRoot = Machine.DeltaNet.observableStateRoot(ceskr)
      deltaNetRoot = Machine.DeltaNet.observableStateRoot(deltaNet)
      _ <- if ceskrRoot == deltaNetRoot then Right(()) else Left(s"CESK-R/DeltaNet differential mismatch for ${program.value}")
    yield DifferentialCertificate(program, instructions.size, Machine.DeltaNet.netRoot(net), ceskrRoot, deltaNetRoot)

  private def processProgramInstructions(graph: Graph, language: EntityId, program: EntityId): Either[String, Vector[Machine.Instr]] =
    for
      policy <- processPolicy(graph, language)
      node <- graph.entity(program).toRight(s"unknown process program ${program.value}")
      _ <- if node.kind == "language.process-program" then Right(()) else Left(s"${program.value} is ${node.kind}, not language.process-program")
      _ <- if node.attrs.get("language").contains(language.value) then Right(()) else Left(s"${program.value} is not owned by ${language.value}")
      entries = node.attrs.toVector.collect { case (key, value) if key.startsWith("step") => key -> EntityId(value) }.sortBy(_._1)
      expected = (1 to entries.size).map(index => f"step$index%02d").toVector
      _ <- if entries.nonEmpty && entries.map(_._1) == expected then Right(()) else Left(s"${program.value} has non-canonical or empty process steps")
      _ <- if entries.size <= policy.maxSteps then Right(()) else Left(s"process program exceeds ${policy.maxSteps} steps")
      instructions <- sequence(entries.map { case (_, entity) => decodeProcessInstruction(graph, language, entity) })
    yield instructions

  private def differentialPolicy(graph: Graph): Either[String, Unit] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "optimization.differential-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        val supported = node.attrs.get("left").contains("ceskr") && node.attrs.get("right").contains("deltanet-independent") && node.attrs.get("comparison").contains("observable-state-root") && node.attrs.get("failure").contains("strict")
        Either.cond(supported, (), s"unsupported differential policy ${entity.value}")
      case Vector() => Left("missing optimization differential policy")
      case _ => Left("multiple optimization differential policies")

  private def differentialWitness(graph: Graph, program: EntityId): Either[String, Unit] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "optimization.differential-witness").map(entity -> _)
    }
    for
      witnesses <- sequence(candidates.map { case (entity, node) =>
        required(node, "program", entity.value).map(value => entity -> EntityId(value))
      })
      matching = witnesses.filter(_._2 == program)
      _ <- matching match
        case Vector(_) => Right(())
        case Vector() => Left(s"missing optimization differential witness for ${program.value}")
        case _ => Left(s"multiple optimization differential witnesses for ${program.value}")
    yield ()

  private def decodeProcessInstruction(graph: Graph, language: EntityId, entity: EntityId): Either[String, Machine.Instr] =
    graph.entity(entity) match
      case None => Left(s"unknown process instruction ${entity.value}")
      case Some(node) if !node.attrs.get("language").contains(language.value) => Left(s"${entity.value} is not owned by ${language.value}")
      case Some(node) => node.kind match
        case "language.process.alloc" =>
          for
            name <- required(node, "name", entity.value)
            modeName <- required(node, "mode", entity.value)
            mode <- modeName match
              case "unrestricted" => Right(Mode.Unrestricted)
              case "affine" => Right(Mode.Affine)
              case "linear" => Right(Mode.Linear)
              case other => Left(s"${entity.value} has invalid resource mode $other")
          yield Machine.Instr.Alloc(name, mode)
        case "language.process.new-channel" => required(node, "channel", entity.value).map(Machine.Instr.NewChannel.apply)
        case "language.process.send" =>
          for
            channel <- required(node, "channel", entity.value)
            resource <- required(node, "resource", entity.value)
          yield Machine.Instr.Send(channel, resource)
        case "language.process.receive" =>
          for
            channel <- required(node, "channel", entity.value)
            process <- required(node, "process", entity.value)
          yield Machine.Instr.Recv(channel, process)
        case "language.process.spawn" =>
          for
            child <- required(node, "child", entity.value)
            captures = node.attrs.get("captures").filter(_.nonEmpty).map(_.split(",", -1).toVector).getOrElse(Vector.empty)
            _ <- if captures.forall(_.nonEmpty) && captures.distinct.size == captures.size then Right(()) else Left(s"${entity.value} has invalid captures")
          yield Machine.Instr.Spawn(child, captures)
        case "language.process.terminate" =>
          required(node, "process", entity.value).map(process => Machine.Instr.Terminate(process, node.attrs.get("result").filter(_.nonEmpty)))
        case "language.process.join" =>
          for
            child <- required(node, "child", entity.value)
            process <- required(node, "process", entity.value)
          yield Machine.Instr.Join(child, process)
        case other => Left(s"unsupported process instruction kind $other at ${entity.value}")

  def sessionPolicy(graph: Graph, language: EntityId): Either[String, SessionPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.session-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          determinism <- required(node, "determinism", entity.value)
          duality <- required(node, "duality", entity.value)
          completion <- required(node, "completion", entity.value)
          failure <- required(node, "failure", entity.value)
          maxTransitions <- node.attrs.get("max-transitions").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-transitions")
          _ <- if determinism == "state-direction-label" && duality == "exact" && completion == "terminal" && failure == "strict" && maxTransitions > 0 && maxTransitions <= 4096 then Right(()) else Left(s"unsupported session policy ${entity.value}")
        yield SessionPolicy(entity, language, determinism, duality, completion, failure, maxTransitions)
      case Vector() => Left(s"missing session policy for ${language.value}")
      case _ => Left(s"multiple session policies for ${language.value}")

  def validateSessionDuality(graph: Graph, language: EntityId, protocol: EntityId): Either[String, Unit] =
    for
      _ <- sessionPolicy(graph, language)
      protocolNode <- sessionProtocolNode(graph, language, protocol)
      dual <- required(protocolNode, "dual", protocol.value).map(EntityId.apply)
      dualNode <- sessionProtocolNode(graph, language, dual)
      reciprocal <- required(dualNode, "dual", dual.value).map(EntityId.apply)
      _ <- if reciprocal == protocol then Right(()) else Left(s"${dual.value} is not reciprocal with ${protocol.value}")
      states <- sessionStates(graph, language, protocol)
      dualStates <- sessionStates(graph, language, dual)
      dualStateById = dualStates.map((entity, node) => entity -> node).toMap
      _ <- sequence(states.map { case (entity, node) =>
        for
          dualState <- required(node, "dual", entity.value).map(EntityId.apply)
          dualStateNode <- dualStateById.get(dualState).toRight(s"${entity.value} names non-dual state ${dualState.value}")
          back <- required(dualStateNode, "dual", dualState.value).map(EntityId.apply)
          _ <- if back == entity then Right(()) else Left(s"${dualState.value} is not reciprocal with ${entity.value}")
          terminal <- required(node, "terminal", entity.value)
          dualTerminal <- required(dualStateNode, "terminal", dualState.value)
          _ <- if terminal == dualTerminal then Right(()) else Left(s"dual states ${entity.value} and ${dualState.value} disagree on completion")
        yield ()
      })
      transitions <- sessionTransitions(graph, language, protocol)
      dualTransitions <- sessionTransitions(graph, language, dual)
      dualTransitionById = dualTransitions.map((entity, node) => entity -> node).toMap
      _ <- sequence(transitions.map { case (entity, node) =>
        for
          dualTransition <- required(node, "dual", entity.value).map(EntityId.apply)
          dualTransitionNode <- dualTransitionById.get(dualTransition).toRight(s"${entity.value} names non-dual transition ${dualTransition.value}")
          back <- required(dualTransitionNode, "dual", dualTransition.value).map(EntityId.apply)
          _ <- if back == entity then Right(()) else Left(s"${dualTransition.value} is not reciprocal with ${entity.value}")
          action <- required(node, "action", entity.value)
          dualAction <- required(dualTransitionNode, "action", dualTransition.value)
          _ <- if (action == "send" && dualAction == "receive") || (action == "receive" && dualAction == "send") then Right(()) else Left(s"${entity.value} and ${dualTransition.value} are not action duals")
          label <- required(node, "label", entity.value)
          dualLabel <- required(dualTransitionNode, "label", dualTransition.value)
          _ <- if label == dualLabel then Right(()) else Left(s"${entity.value} and ${dualTransition.value} disagree on label")
          from <- required(node, "from", entity.value).map(EntityId.apply)
          to <- required(node, "to", entity.value).map(EntityId.apply)
          dualFrom <- required(dualTransitionNode, "from", dualTransition.value).map(EntityId.apply)
          dualTo <- required(dualTransitionNode, "to", dualTransition.value).map(EntityId.apply)
          expectedFrom <- graph.entity(from).flatMap(_.attrs.get("dual")).map(EntityId.apply).toRight(s"${from.value} lacks dual state")
          expectedTo <- graph.entity(to).flatMap(_.attrs.get("dual")).map(EntityId.apply).toRight(s"${to.value} lacks dual state")
          _ <- if dualFrom == expectedFrom && dualTo == expectedTo then Right(()) else Left(s"${entity.value} and ${dualTransition.value} disagree on state duals")
        yield ()
      })
    yield ()

  def runSession(
      graph: Graph,
      language: EntityId,
      protocol: EntityId,
      actions: Vector[SessionAction]
  ): Either[String, SessionResult] =
    for
      policy <- sessionPolicy(graph, language)
      _ <- validateSessionDuality(graph, language, protocol)
      _ <- if actions.size <= policy.maxTransitions then Right(()) else Left(s"session exceeds ${policy.maxTransitions} transitions")
      protocolNode <- sessionProtocolNode(graph, language, protocol)
      initial <- required(protocolNode, "initial", protocol.value).map(EntityId.apply)
      states <- sessionStates(graph, language, protocol)
      stateById = states.toMap
      _ <- stateById.get(initial).toRight(s"${protocol.value} names unknown initial state ${initial.value}")
      transitions <- sessionTransitions(graph, language, protocol)
      _ <- transitions.groupBy { case (_, node) => (node.attrs.get("from"), node.attrs.get("action"), node.attrs.get("label")) }.collectFirst {
        case (key, duplicates) if duplicates.size > 1 => key
      }.toLeft(()).left.map(key => s"non-deterministic session transition key $key")
      result <- actions.foldLeft[Either[String, (EntityId, Vector[EntityId])]](Right(initial -> Vector.empty)) { case (acc, action) =>
        acc.flatMap { case (state, used) =>
          transitions.filter { case (_, node) =>
            node.attrs.get("from").contains(state.value) && node.attrs.get("action").contains(action.direction) && node.attrs.get("label").contains(action.label)
          } match
            case Vector((transition, node)) => required(node, "to", transition.value).map(to => EntityId(to) -> (used :+ transition))
            case Vector() => Left(s"session ${protocol.value} rejects ${action.direction}:${action.label} at ${state.value}")
            case _ => Left(s"ambiguous session action ${action.direction}:${action.label} at ${state.value}")
        }
      }
      (finalState, used) = result
      finalNode <- stateById.get(finalState).toRight(s"session reached unknown state ${finalState.value}")
      terminal <- required(finalNode, "terminal", finalState.value)
      _ <- if terminal == "true" then Right(()) else Left(s"session ${protocol.value} did not reach a terminal state")
    yield SessionResult(protocol, finalState, used)

  private def sessionProtocolNode(graph: Graph, language: EntityId, protocol: EntityId): Either[String, Node] =
    graph.entity(protocol) match
      case Some(node) if node.kind == "language.session-protocol" && node.attrs.get("language").contains(language.value) => Right(node)
      case Some(node) => Left(s"${protocol.value} is not a ${language.value} session protocol (${node.kind})")
      case None => Left(s"unknown session protocol ${protocol.value}")

  private def sessionStates(graph: Graph, language: EntityId, protocol: EntityId): Either[String, Vector[(EntityId, Node)]] =
    val states = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.session-state" && node.attrs.get("protocol").contains(protocol.value)).map(entity -> _)
    }
    if states.nonEmpty && states.forall(_._2.attrs.get("language").contains(language.value)) then Right(states)
    else Left(s"${protocol.value} has no states or cross-language states")

  private def sessionTransitions(graph: Graph, language: EntityId, protocol: EntityId): Either[String, Vector[(EntityId, Node)]] =
    val transitions = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.session-transition" && node.attrs.get("protocol").contains(protocol.value)).map(entity -> _)
    }
    if transitions.nonEmpty && transitions.forall(_._2.attrs.get("language").contains(language.value)) then Right(transitions)
    else Left(s"${protocol.value} has no transitions or cross-language transitions")

  def runCeskr(graph: Graph, language: EntityId, expression: EntityId): Either[String, CeskrResult] =
    for
      policy <- ceskrPolicy(graph, language)
      rules <- ceskrRules(graph, language)
      byForm <- rules.groupBy(_.form).toVector.collectFirst { case (form, duplicates) if duplicates.size > 1 => form } match
        case Some(form) => Left(s"ambiguous CESK-R rules for $form")
        case None => Right(rules.map(rule => rule.form -> rule).toMap)
      controls <- ceskrControls(graph, language, expression, Set.empty)
      _ <- if controls.size <= policy.maxTransitions then Right(()) else Left(s"CESK-R derivation exceeds ${policy.maxTransitions} transitions")
      transitions <- sequence(controls.zipWithIndex.map { case ((control, form), index) =>
        byForm.get(form).toRight(s"missing CESK-R rule for $form").flatMap { rule =>
          val expected = Map(
            "language.expr.reference" -> "lookup-environment",
            "language.expr.lambda" -> "close-environment",
            "language.expr.apply" -> "evaluate-function-then-argument",
            "language.expr.construct" -> "evaluate-fields-left-to-right",
            "language.expr.match" -> "evaluate-scrutinee-then-select"
          ).get(form)
          if expected.contains(rule.action) then Right(CeskrTransition(index, rule.entity, control, rule.action))
          else Left(s"unsupported CESK-R action ${rule.action} for $form")
        }
      })
      evaluated <- evaluate(graph, language, expression)
    yield CeskrResult(evaluated.value, transitions)

  private def ceskrPolicy(graph: Graph, language: EntityId): Either[String, CeskrPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.ceskr-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          selection <- required(node, "selection", entity.value)
          order <- required(node, "order", entity.value)
          resources <- required(node, "resources", entity.value)
          failure <- required(node, "failure", entity.value)
          max <- node.attrs.get("max-transitions").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-transitions")
          _ <- if selection == "exact-form" && order == "control-before-children" && resources == "inherited" && failure == "strict" && max > 0 && max <= 4096 then Right(()) else Left(s"unsupported CESK-R policy ${entity.value}")
        yield CeskrPolicy(entity, language, selection, order, resources, failure, max)
      case Vector() => Left(s"missing CESK-R policy for ${language.value}")
      case _ => Left(s"multiple CESK-R policies for ${language.value}")

  private def ceskrRules(graph: Graph, language: EntityId): Either[String, Vector[CeskrRule]] =
    sequence(graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(node => node.kind == "language.ceskr-rule" && node.attrs.get("language").contains(language.value)).map { node =>
        for
          form <- required(node, "form", entity.value)
          action <- required(node, "action", entity.value)
        yield CeskrRule(entity, form, action)
      }
    }).flatMap(rules => if rules.nonEmpty then Right(rules) else Left(s"missing CESK-R rules for ${language.value}"))

  private def ceskrControls(graph: Graph, language: EntityId, entity: EntityId, path: Set[EntityId]): Either[String, Vector[(EntityId, String)]] =
    if path.contains(entity) then Left(s"cyclic CESK-R control graph at ${entity.value}")
    else graph.entity(entity) match
      case None => Left(s"unknown CESK-R control ${entity.value}")
      case Some(node) if !node.attrs.get("language").contains(language.value) => Left(s"${entity.value} is not owned by ${language.value}")
      case Some(node) =>
        val children: Either[String, Vector[EntityId]] = node.kind match
          case "language.expr.reference" => Right(Vector.empty)
          case "language.expr.lambda" => required(node, "body", entity.value).map(value => Vector(EntityId(value)))
          case "language.expr.apply" =>
            for
              function <- required(node, "function", entity.value)
              argument <- required(node, "argument", entity.value)
            yield Vector(EntityId(function), EntityId(argument))
          case "language.expr.construct" =>
            val values = node.attrs.toVector.collect { case (key, value) if key.startsWith("field") && key.endsWith("-value") => key -> EntityId(value) }.sortBy(_._1).map(_._2)
            Right(values)
          case "language.expr.match" =>
            for
              scrutinee <- required(node, "scrutinee", entity.value)
              cases = node.attrs.toVector.collect { case (key, value) if key.startsWith("case") => key -> EntityId(value) }.sortBy(_._1)
              bodies <- sequence(cases.map { case (_, caseEntity) => graph.entity(caseEntity).toRight(s"unknown match case ${caseEntity.value}").flatMap(caseNode => required(caseNode, "body", caseEntity.value).map(EntityId.apply)) })
            yield EntityId(scrutinee) +: bodies
          case other => Left(s"unsupported CESK-R control form $other")
        children.flatMap(items => sequence(items.map(child => ceskrControls(graph, language, child, path + entity))).map(nested => (entity -> node.kind) +: nested.flatten))

  def encodeCeskrTrace(graph: Graph, language: EntityId, expression: EntityId): Either[String, String] =
    for
      _ <- tracePolicy(graph, language)
      result <- runCeskr(graph, language, expression)
      constructor <- result.value match
        case SemanticValue.Data(entity, _) => Right(entity)
        case _: SemanticValue.Closure => Left("canonical CESK-R traces require a data result")
      records = result.transitions.map(t => Canon.record("transition", Vector(t.index.toString, t.rule.value, t.control.value, t.action)))
    yield Canon.record("ceskr-trace-v1", Vector(language.value, expression.value, constructor.value, Canon.record("transitions", records)))

  def decodeCeskrTrace(graph: Graph, language: EntityId, encoded: String): Either[String, CeskrTrace] =
    for
      _ <- tracePolicy(graph, language)
      fields <- Canon.fixed(encoded, "ceskr-trace-v1", 4)
      _ <- if fields(0) == language.value then Right(()) else Left(s"trace language ${fields(0)} != ${language.value}")
      expression = EntityId(fields(1))
      _ <- graph.entity(expression).toRight(s"trace names unknown expression ${expression.value}")
      constructor = EntityId(fields(2))
      _ <- graph.entity(constructor).filter(_.kind == "language.constructor").toRight(s"trace names invalid constructor ${constructor.value}")
      records <- Canon.fields(fields(3), "transitions")
      transitions <- sequence(records.zipWithIndex.map { case (record, expectedIndex) =>
        for
          values <- Canon.fixed(record, "transition", 4)
          index <- values(0).toIntOption.toRight(s"trace transition has invalid index ${values(0)}")
          _ <- if index == expectedIndex then Right(()) else Left(s"trace transition index $index != $expectedIndex")
          rule = EntityId(values(1))
          _ <- graph.entity(rule).filter(_.kind == "language.ceskr-rule").toRight(s"trace names invalid rule ${rule.value}")
          control = EntityId(values(2))
          _ <- graph.entity(control).toRight(s"trace names unknown control ${control.value}")
        yield CeskrTransition(index, rule, control, values(3))
      })
      trace = CeskrTrace(expression, constructor, transitions)
      _ <- if encodeDecodedTrace(language, trace) == encoded then Right(()) else Left("non-canonical CESK-R trace encoding")
    yield trace

  def replayCeskrTrace(graph: Graph, language: EntityId, encoded: String): Either[String, CeskrResult] =
    for
      trace <- decodeCeskrTrace(graph, language, encoded)
      result <- runCeskr(graph, language, trace.expression)
      constructor <- result.value match
        case SemanticValue.Data(entity, _) => Right(entity)
        case _: SemanticValue.Closure => Left("replayed CESK-R trace produced a closure")
      _ <- if constructor == trace.valueConstructor && result.transitions == trace.transitions then Right(()) else Left("CESK-R trace replay mismatch")
    yield result

  private def encodeDecodedTrace(language: EntityId, trace: CeskrTrace): String =
    val records = trace.transitions.map(t => Canon.record("transition", Vector(t.index.toString, t.rule.value, t.control.value, t.action)))
    Canon.record("ceskr-trace-v1", Vector(language.value, trace.expression.value, trace.valueConstructor.value, Canon.record("transitions", records)))

  private def tracePolicy(graph: Graph, language: EntityId): Either[String, TracePolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(node => node.kind == "language.trace-policy" && node.attrs.get("language").contains(language.value)).map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          format <- required(node, "format", entity.value)
          offsets <- required(node, "indices", entity.value)
          identity <- required(node, "identity", entity.value)
          failure <- required(node, "failure", entity.value)
          _ <- if format == "canonical-v1" && offsets == "zero-based-contiguous" && identity == "entity" && failure == "strict" then Right(()) else Left(s"unsupported trace policy ${entity.value}")
        yield TracePolicy(entity, language, format, offsets, identity, failure)
      case Vector() => Left(s"missing trace policy for ${language.value}")
      case _ => Left(s"multiple trace policies for ${language.value}")

  private def decodeModule(entity: EntityId, node: Node): Either[String, ModuleSpec] =
    for
      language <- required(node, "language", entity.value).map(EntityId.apply)
      name <- required(node, "name", entity.value)
    yield ModuleSpec(entity, language, name)

  private def moduleBindings(graph: Graph, moduleIds: Set[EntityId]): Either[String, Vector[NameBinding]] =
    sequence(graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(_.kind == "language.name-binding").map { node =>
        for
          module <- required(node, "module", entity.value).map(EntityId.apply)
          _ <- if moduleIds.contains(module) then Right(()) else Left(s"${entity.value} names unknown module ${module.value}")
          name <- required(node, "name", entity.value)
          namespace <- required(node, "namespace", entity.value)
          target <- required(node, "target", entity.value).map(EntityId.apply)
          _ <- graph.entity(target).toRight(s"${entity.value} names unknown target ${target.value}")
          visibility <- required(node, "visibility", entity.value)
          _ <- if Set("private", "public").contains(visibility) then Right(()) else Left(s"${entity.value} has invalid visibility $visibility")
        yield NameBinding(entity, module, name, namespace, target, visibility)
      }
    }).flatMap { values =>
      values.groupBy(binding => (binding.module, binding.namespace, binding.name)).collectFirst {
        case ((module, namespace, name), duplicates) if duplicates.size > 1 => s"duplicate $namespace binding $name in ${module.value}"
      }.toLeft(values)
    }

  private def moduleImports(graph: Graph, moduleIds: Set[EntityId]): Either[String, Vector[ModuleImport]] =
    sequence(graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(_.kind == "language.module-import").map { node =>
        for
          module <- required(node, "module", entity.value).map(EntityId.apply)
          imported <- required(node, "imported", entity.value).map(EntityId.apply)
          _ <- if moduleIds.contains(module) && moduleIds.contains(imported) then Right(()) else Left(s"${entity.value} has an unknown module endpoint")
          mode <- required(node, "mode", entity.value)
          _ <- if Set("open", "qualified").contains(mode) then Right(()) else Left(s"${entity.value} has invalid import mode $mode")
          alias = node.attrs.get("alias").filter(_.nonEmpty)
          _ <- if mode == "qualified" && alias.isEmpty then Left(s"${entity.value} qualified import lacks an alias") else Right(())
        yield ModuleImport(entity, module, imported, alias, mode)
      }
    })

  private def resolveUnqualified(
      module: EntityId,
      name: String,
      namespace: String,
      bindings: Vector[NameBinding],
      imports: Vector[ModuleImport],
      visited: Set[EntityId]
  ): Either[String, ResolvedName] =
    if visited.contains(module) then Left(s"cyclic open import while resolving $name")
    else
      bindings.filter(binding => binding.module == module && binding.name == name && binding.namespace == namespace) match
        case Vector(local) => Right(ResolvedName(name, local, Vector(module)))
        case Vector() =>
          val candidates = imports.filter(item => item.module == module && item.mode == "open").flatMap { item =>
            resolveExported(item.imported, name, namespace, bindings, imports, visited + module).toOption.map(binding => (item.imported, binding))
          }.distinctBy(_._2.target)
          candidates match
            case Vector((imported, binding)) => Right(ResolvedName(name, binding, Vector(module, imported)))
            case Vector() => Left(s"unresolved $namespace name $name in ${module.value}")
            case many => Left(s"ambiguous $namespace name $name in ${module.value}: ${many.map(_._2.target.value).sorted.mkString(", ")}")
        case _ => Left(s"ambiguous local $namespace name $name in ${module.value}")

  private def resolveExported(
      module: EntityId,
      name: String,
      namespace: String,
      bindings: Vector[NameBinding],
      imports: Vector[ModuleImport],
      visited: Set[EntityId]
  ): Either[String, NameBinding] =
    resolveUnqualified(module, name, namespace, bindings, imports, visited).flatMap { resolved =>
      if resolved.binding.visibility == "public" then Right(resolved.binding)
      else Left(s"${resolved.binding.entity.value} is private")
    }

  /**
   * Parse with the ordinary strict parser first. On failure, TypedHoleDiagnostics may
   * perform one graph-authorized hole insertion at a real-token boundary.
   * Earlier grammar versions remain strictly fail-closed.
   */
  def parseRecovering(graph: Graph, language: EntityId, text: String): Either[String, RecoveredParse] =
    parse(graph, language, text) match
      case Right(tree) => Right(RecoveredParse(tree, Vector.empty, Vector.empty))
      case Left(strictError) =>
        for
          spec <- definition(graph, language)
          encoding <- grammarEncoding(graph, spec)
          _ <- if encoding == "recovery-grammar-graph" then Right(()) else Left(strictError)
          recovery <- recoveryPolicyDefinition(graph, spec)
          diagnostics <- diagnosticPolicyDefinition(graph, spec)
          _ <- if recovery.maxEdits == 1 && recovery.selection == "unique-edit" && recovery.candidateBoundaries == "real-token-ends" && recovery.failure == "strict" then Right(()) else Left("unsupported recovery policy")
          _ <- if recovery.strategies == Vector(EntityId("grammar.recovery.strategy.insert-hole")) then Right(()) else Left("unsupported recovery strategy set")
          document <- lexSource(graph, language, text).left.map(err => s"$strictError; recovery lex failed: $err")
          byteLength = text.getBytes(StandardCharsets.UTF_8).length
          positions = (document.tokens.map(_.span.endByte) :+ byteLength).distinct.sorted
          attempts = positions.flatMap { at =>
            insertUtf8(text, at, recovery.hole.surface).toOption.flatMap { edited =>
              parse(graph, language, edited).toOption.map(tree => (at, tree))
            }
          }.distinct
          recovered <- attempts match
            case Vector((at, tree)) =>
              val span = SourceSpan(at, at)
              val edit = RecoveryEdit(recovery.strategies.head, span, recovery.hole.surface)
              val diagnostic = ParseDiagnostic(
                diagnostics.insertedHoleCode,
                diagnostics.severity,
                s"inserted ${recovery.hole.sort} hole",
                span,
                Vector(recovery.hole.sort)
              )
              Right(RecoveredParse(tree, Vector(diagnostic), Vector(edit)))
            case Vector() => Left(s"$strictError; TypedHoleDiagnostics recovery found no unique edit")
            case many => Left(s"$strictError; TypedHoleDiagnostics recovery is ambiguous across ${many.size} edits")
        yield recovered

  /** Parse semantically while retaining the exact SourceProvenance source document and a SourceMapLayout AST source map. */
  def parseWithSource(graph: Graph, language: EntityId, text: String): Either[String, ParsedSource] =
    for
      spec <- definition(graph, language)
      encoding <- grammarEncoding(graph, spec)
      _ <- if Set("lexical-source-layout-graph", "layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then Right(()) else Left(s"${spec.grammar.value} does not select source maps")
      _ <- sourceMapPolicyDefinition(graph, spec)
      document <- lexSource(graph, language, text)
      tree <- parse(graph, language, text)
      map <- buildAstSourceMap(graph, language, tree, document)
    yield ParsedSource(tree, document, map)

  /** Generate virtual offside-layout events without disturbing lossless source reconstruction. */
  def layoutSource(graph: Graph, language: EntityId, text: String): Either[String, LayoutDocument] =
    for
      spec <- definition(graph, language)
      encoding <- grammarEncoding(graph, spec)
      _ <- if Set("lexical-source-layout-graph", "layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then Right(()) else Left(s"${spec.grammar.value} does not select graph layout")
      policy <- layoutPolicyDefinition(graph, spec)
      document <- lexSource(graph, language, text)
      lexer <- lexerDefinition(graph, spec)
      events <- deriveLayoutEvents(text, policy, lexer.lineComment)
    yield LayoutDocument(document, events)

  /** Lossless lexical provenance. Semantic parsing remains available through parse. */
  def lexSource(graph: Graph, language: EntityId, text: String): Either[String, SourceDocument] =
    for
      spec <- definition(graph, language)
      encoding <- grammarEncoding(graph, spec)
      _ <- if Set("lexical-source-graph", "lexical-source-layout-graph", "layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then Right(()) else Left(s"${spec.grammar.value} does not select lossless source provenance")
      _ <- sourcePolicyDefinition(graph, spec)
      prods <- productions(graph, language)
      lexer <- lexerDefinition(graph, spec)
      literals = prods.flatMap(_.body.toVector.flatMap(grammarLiterals)).distinct.sorted
      rules <- lexerRuleDefinitions(graph, spec)
      document <- scanLexicalGraphDocument(text, literals, rules, lexer)
      _ <- if document.renderLossless == text then Right(()) else Left("lossless source reconstruction failed")
    yield document

  def changeLanguage(graph: Graph, language: EntityId): Either[String, ChangeLanguageSpec] =
    definition(graph, language).flatMap { spec =>
      graph.entity(spec.changes) match
        case Some(node) if node.kind == "language.change-language" =>
          for
            basis <- required(node, "basis", spec.changes.value)
            free <- node.attrs.get("free").flatMap(_.toBooleanOption).toRight(s"${spec.changes.value} lacks boolean free")
            allowed <- required(node, "allowed-ops", spec.changes.value)
            owner <- sourceEntityAt(graph, spec.changes, "language")
            _ <- if owner == language then Right(()) else Left(s"${spec.changes.value} belongs to ${owner.value}, not ${language.value}")
          yield ChangeLanguageSpec(spec.changes, language, basis, free, splitSet(allowed))
        case Some(node) => Left(s"${spec.changes.value} is ${node.kind}, not language.change-language")
        case None => Left(s"missing change language ${spec.changes.value}")
    }

  def changeOperators(graph: Graph, language: EntityId): Either[String, Vector[ChangeOperator]] =
    changeLanguage(graph, language).flatMap { changes =>
      graph.entities.get(changes.entity).toRight(s"missing ${changes.entity.value}").flatMap { changeId =>
        val changeNode = graph.nodes(changeId)
        val ports = changeNode.ports.filter(p => p.direction == Direction.In && p.name.startsWith("op")).sortBy(_.name)
        sequence(ports.map { p =>
          for
            entity <- sourceEntityAt(graph, changes.entity, p.name)
            node <- graph.entity(entity).toRight(s"missing change operator ${entity.value}")
            _ <- if node.kind == "language.change-operator" then Right(()) else Left(s"${entity.value} is ${node.kind}, not language.change-operator")
            primitive <- required(node, "primitive", entity.value)
            targetKinds <- required(node, "target-kinds", entity.value)
          yield ChangeOperator(entity, primitive, splitSet(targetKinds))
        })
      }
    }

  /** Parse text with the graph-selected grammar representation. */
  def parse(graph: Graph, language: EntityId, text: String): Either[String, Tree] =
    for
      spec <- definition(graph, language)
      startNode <- graph.entity(spec.startSort).toRight(s"missing start sort ${spec.startSort.value}")
      startSort <- required(startNode, "name", spec.startSort.value)
      encoding <- grammarEncoding(graph, spec)
      prods <- productions(graph, language)
      tokens <- tokenize(graph, spec, prods, text)
      _ <- if tokens.nonEmpty then Right(()) else Left("cannot parse empty input")
      candidates =
        val parsed =
          if Set("combinator-graph", "capture-graph", "lexer-graph", "lexical-graph", "lexical-codec-graph", "lexical-source-graph", "lexical-source-layout-graph", "layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then parseSortPrecedence(prods, tokens, startSort, 0, 0, 0)
          else parseSort(prods, tokens, startSort, 0, 0)
        parsed.filter(_._2 == tokens.size).map(_._1).distinct
      tree <- candidates match
        case Vector(one) => Right(one)
        case Vector() => Left(s"input does not match ${spec.name}/$startSort")
        case many => Left(s"ambiguous ${spec.name} parse: ${many.size} complete trees")
    yield tree

  /** Print an AST using the same graph-derived productions used by the parser. */
  def print(graph: Graph, language: EntityId, tree: Tree): Either[String, String] =
    for
      spec <- definition(graph, language)
      _ <- grammarPolicy(graph, spec)
      prods <- productions(graph, language)
      rendered <- printTree(graph, language, prods, tree)
      text <- renderPrintPieces(graph, spec, rendered)
    yield text

  /** A graph-derived convenience operation in the associated free changes language. */
  def replacePreservingEdges(
      graph: Graph,
      language: EntityId,
      target: EntityId,
      replacement: Node,
      message: String,
      author: String = "ai"
  ): Either[String, Change] =
    for
      operator <- operatorFor(graph, language, "replace-preserving-edges")
      oldId <- graph.entities.get(target).toRight(s"unknown language entity ${target.value}")
      old <- graph.nodes.get(oldId).toRight(s"missing content for ${target.value}")
      _ <- if old.kind == replacement.kind then Right(()) else Left(s"replacement kind ${replacement.kind} != ${old.kind}")
      _ <- if operator.targetKinds.contains(replacement.kind) then Right(()) else Left(s"${operator.entity.value} does not admit ${replacement.kind}")
      _ <- if replacement.attrs.get("language").contains(language.value) then Right(()) else Left("replacement is not owned by the selected language")
      incident = graph.edges.toVector.filter { case (_, edge) => edge.from.node == oldId || edge.to.node == oldId }.sortBy(_._1.value)
      replacementId = Canon.nodeId(replacement)
      rewired = incident.map { case (_, edge) =>
        edge.copy(
          from = if edge.from.node == oldId then edge.from.copy(node = replacementId) else edge.from,
          to = if edge.to.node == oldId then edge.to.copy(node = replacementId) else edge.to
        )
      }.sortBy(edge => Canon.edgeId(edge).value)
      ops = incident.map { case (id, _) => Op.Disconnect(id) } ++
        Vector(Op.ReplaceEntity(target, replacement)) ++
        rewired.map(Op.Connect.apply)
      change = Change(Set.empty, ops, message, author)
      _ <- admitChange(graph, language, change).map(_ => ())
    yield change

  /**
   * Admit a DeltaTrellis change as a member of the graph-associated free
   * language change language, then return the validated successor graph.
   */
  def admitChange(graph: Graph, language: EntityId, change: Change): Either[String, Graph] =
    for
      policy <- changeLanguage(graph, language)
      _ <- if policy.basis == "DeltaTrellis" && policy.free then Right(()) else Left("change language is not a free DeltaTrellis specialization")
      _ <- validateOperationVocabulary(policy, change)
      successor <- Delta.applyChange(graph, change)
      _ <- validateOwnership(graph, successor, language, change)
      constitutional = Check.validate(successor)
      _ <- if constitutional.isEmpty then Right(()) else Left(constitutional.mkString("constitutional validation failed: ", "; ", ""))
      languageErrors = definitionErrors(successor)
      _ <- if languageErrors.isEmpty then Right(()) else Left(languageErrors.mkString("language validation failed: ", "; ", ""))
    yield successor

  def definitionErrors(graph: Graph): Vector[String] =
    val errors = Vector.newBuilder[String]

    val defs = graph.entities.toVector.sortBy(_._1.value).collect {
      case (entity, id) if graph.nodes.get(id).exists(_.kind == "language.definition") => entity
    }
    defs.foreach { language =>
      definition(graph, language) match
        case Left(error) => errors += error
        case Right(spec) =>
          val startSortName = graph.entity(spec.startSort).flatMap(_.attrs.get("name"))
          graph.entity(spec.startSort).foreach { node =>
            if node.attrs.get("language") != Some(language.value) then errors += s"${spec.startSort.value} is not owned by ${language.value}"
          }
          graph.entity(spec.grammar).foreach { node =>
            if node.attrs.get("language") != Some(language.value) then errors += s"${spec.grammar.value} is not owned by ${language.value}"
            if node.attrs.get("start-sort") != startSortName then errors += s"${spec.grammar.value} start-sort does not match ${spec.startSort.value}"

          }
          grammarPolicy(graph, spec).left.foreach(errors += _)
          grammarEncoding(graph, spec) match
            case Right("expression-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("expression-grammar.schema")) then
                errors += s"${spec.grammar.value} does not select expression-grammar.schema"
              val prodEntities = graph.entity(spec.grammar).toVector.flatMap(_.ports)
                .filter(p => p.direction == Direction.In && p.name.startsWith("p"))
                .flatMap(p => sourceEntityAt(graph, spec.grammar, p.name).toOption)
              prodEntities.foreach { prodEntity =>
                graph.entity(prodEntity).foreach { prodNode =>
                  if prodNode.attrs.contains("pattern") then errors += s"${prodEntity.value} still embeds a pattern string under ExpressionGrammar"
                  if sourceEntityAt(graph, prodEntity, "body").isLeft then errors += s"${prodEntity.value} lacks a first-class grammar body"
                }
              }
            case Right("combinator-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("grammar-combinators.schema")) then
                errors += s"${spec.grammar.value} does not select grammar-combinators.schema"
              validateCombinatorProductions(graph, spec.grammar, "GrammarCombinators").foreach(error => errors += error)
            case Right("capture-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("typed-captures.schema")) then
                errors += s"${spec.grammar.value} does not select typed-captures.schema"
              validateCombinatorProductions(graph, spec.grammar, "TypedCaptures").foreach(error => errors += error)
            case Right("lexer-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("graph-lexer.schema")) then
                errors += s"${spec.grammar.value} does not select graph-lexer.schema"
              validateCombinatorProductions(graph, spec.grammar, "GraphLexer").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
            case Right("lexical-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("lexical-expressions.schema")) then
                errors += s"${spec.grammar.value} does not select lexical-expressions.schema"
              validateCombinatorProductions(graph, spec.grammar, "LexicalExpressions").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec).left.foreach(errors += _)
            case Right("lexical-codec-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("lexical-codecs.schema")) then
                errors += s"${spec.grammar.value} does not select lexical-codecs.schema"
              validateCombinatorProductions(graph, spec.grammar, "LexicalCodecs").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a LexicalCodecs lexer rule without a codec"
                case Right(_) => ()
            case Right("lexical-source-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("source-provenance.schema")) then
                errors += s"${spec.grammar.value} does not select source-provenance.schema"
              validateCombinatorProductions(graph, spec.grammar, "SourceProvenance").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a SourceProvenance lexer rule without a codec"
                case Right(_) => ()
              sourcePolicyDefinition(graph, spec).left.foreach(errors += _)
            case Right("lexical-source-layout-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("source-map-layout.schema")) then
                errors += s"${spec.grammar.value} does not select source-map-layout.schema"
              validateCombinatorProductions(graph, spec.grammar, "SourceMapLayout").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a SourceMapLayout lexer rule without a codec"
                case Right(_) => ()
              sourcePolicyDefinition(graph, spec).left.foreach(errors += _)
              sourceMapPolicyDefinition(graph, spec).left.foreach(errors += _)
              layoutPolicyDefinition(graph, spec).left.foreach(errors += _)
            case Right("layout-grammar-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("virtual-layout.schema")) then
                errors += s"${spec.grammar.value} does not select virtual-layout.schema"
              validateCombinatorProductions(graph, spec.grammar, "VirtualLayout").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a VirtualLayout lexer rule without a codec"
                case Right(_) => ()
              sourcePolicyDefinition(graph, spec).left.foreach(errors += _)
              sourceMapPolicyDefinition(graph, spec).left.foreach(errors += _)
              layoutPolicyDefinition(graph, spec).left.foreach(errors += _)
            case Right("suite-grammar-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("statement-suites.schema")) then
                errors += s"${spec.grammar.value} does not select statement-suites.schema"
              validateCombinatorProductions(graph, spec.grammar, "StatementSuites").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a StatementSuites lexer rule without a codec"
                case Right(_) => ()
              sourcePolicyDefinition(graph, spec).left.foreach(errors += _)
              sourceMapPolicyDefinition(graph, spec).left.foreach(errors += _)
              layoutPolicyDefinition(graph, spec).left.foreach(errors += _)
              productions(graph, spec.entity) match
                case Left(error) => errors += error
                case Right(prods) if prods.exists(p => !Set("simple", "compound").contains(p.statementForm)) => errors += s"${spec.grammar.value} has an invalid statement form"
                case Right(_) => ()
            case Right("recovery-grammar-graph") =>
              if sourceEntityAt(graph, spec.grammar, "schema") != Right(EntityId("typed-hole-diagnostics.schema")) then
                errors += s"${spec.grammar.value} does not select typed-hole-diagnostics.schema"
              validateCombinatorProductions(graph, spec.grammar, "TypedHoleDiagnostics").foreach(error => errors += error)
              lexerDefinition(graph, spec).left.foreach(errors += _)
              lexerRuleDefinitions(graph, spec) match
                case Left(error) => errors += error
                case Right(rules) if rules.exists(_.codec.isEmpty) => errors += s"${spec.grammar.value} has a TypedHoleDiagnostics lexer rule without a codec"
                case Right(_) => ()
              sourcePolicyDefinition(graph, spec).left.foreach(errors += _)
              sourceMapPolicyDefinition(graph, spec).left.foreach(errors += _)
              layoutPolicyDefinition(graph, spec).left.foreach(errors += _)
              recoveryPolicyDefinition(graph, spec).left.foreach(errors += _)
              diagnosticPolicyDefinition(graph, spec).left.foreach(errors += _)
              productions(graph, spec.entity) match
                case Left(error) => errors += error
                case Right(prods) if prods.exists(p => !Set("simple", "compound").contains(p.statementForm)) => errors += s"${spec.grammar.value} has an invalid statement form"
                case Right(prods) if prods.count(_.constructor == EntityId("language.bool0.ctor.Hole")) != 1 => errors += s"${spec.grammar.value} must expose exactly one typed hole production"
                case Right(_) => ()
            case _ => ()
          changeLanguage(graph, language) match
            case Left(error) => errors += error
            case Right(changes) =>
              if changes.basis != "DeltaTrellis" then errors += s"${changes.entity.value} basis is ${changes.basis}, expected DeltaTrellis"
              if !changes.free then errors += s"${changes.entity.value} is not free"
              val requiredOps = Set("add-node", "bind-entity", "replace-entity", "remove-entity", "connect", "disconnect")
              if !requiredOps.subsetOf(changes.allowedOps) then errors += s"${changes.entity.value} does not expose the required DeltaTrellis generators"
          changeOperators(graph, language) match
            case Left(error) => errors += error
            case Right(operators) =>
              if !operators.exists(_.primitive == "replace-preserving-edges") then errors += s"${language.value} lacks replace-preserving-edges"
              if !operators.exists(_.primitive == "remove-with-edges") then errors += s"${language.value} lacks remove-with-edges"

          productions(graph, language) match
            case Left(error) => errors += error
            case Right(prods) =>
              val priorities = prods.map(_.priority)
              if priorities.distinct.size != priorities.size then errors += s"${language.value} grammar contains duplicate priorities"
              prods.foreach { prod =>
                if prod.body.isEmpty && !prod.pattern.headOption.exists { case PatternItem.Literal(_) => true; case _ => false } then
                  errors += s"${prod.entity.value} must begin with a literal"
                if prod.body.nonEmpty && Set("atom", "prefix", "infix", "group").contains(prod.fixity) == false then
                  errors += s"${prod.entity.value} has unsupported fixity ${prod.fixity}"
                if prod.fixity == "infix" && prod.associativity == "none" then
                  errors += s"${prod.entity.value} infix production lacks associativity"
                if prod.fixity != "infix" && !Set("none", "right").contains(prod.associativity) then
                  errors += s"${prod.entity.value} has invalid non-infix associativity ${prod.associativity}"
                if prod.fixity == "infix" && prod.body.flatMap(body => infixShape(body, prod.sort)).isEmpty then
                  errors += s"${prod.entity.value} is not a canonical binary infix body"
                graph.entity(prod.constructor) match
                  case Some(ctorNode) if ctorNode.kind == "language.constructor" =>
                    decodeConstructor(graph, prod.constructor, ctorNode) match
                      case Left(error) => errors += error
                      case Right(ctor) =>
                        if ctorNode.attrs.get("language") != Some(language.value) then errors += s"${prod.constructor.value} is not owned by ${language.value}"
                        if ctor.sort != prod.sort then errors += s"${prod.entity.value} sort ${prod.sort} != constructor sort ${ctor.sort}"
                        val ctorSort = sourceEntityAt(graph, prod.constructor, "sort").flatMap { entity =>
                          graph.entity(entity).flatMap(_.attrs.get("name")).toRight(s"${entity.value} lacks sort name")
                        }
                        if ctorSort != Right(ctor.sort) then errors += s"${prod.constructor.value} sort edge does not denote ${ctor.sort}"
                        val prodSort = sourceEntityAt(graph, prod.entity, "sort").flatMap { entity =>
                          graph.entity(entity).flatMap(_.attrs.get("name")).toRight(s"${entity.value} lacks sort name")
                        }
                        if prodSort != Right(prod.sort) then errors += s"${prod.entity.value} sort edge does not denote ${prod.sort}"
                        val refs = prod.body.map(captureSignature).getOrElse(prod.pattern.collect { case PatternItem.Field(name, sort) => FieldSpec(name, sort, Cardinality.One) })
                        if refs != ctor.fields then errors += s"${prod.entity.value} grammar fields $refs != constructor fields ${ctor.fields}"
                  case Some(node) => errors += s"${prod.constructor.value} has kind ${node.kind}, expected language.constructor"
                  case None => errors += s"missing constructor ${prod.constructor.value}"
              }
    }

    val hasNameResolution = graph.nodes.values.exists(node =>
      node.kind == "language.name-policy" || node.kind == "language.module" || node.kind == "language.name-binding" || node.kind == "language.module-import"
    )
    if hasNameResolution then
      languages(graph).foreach { language =>
        namePolicy(graph, language.entity).left.foreach(errors += _)
        modules(graph, language.entity) match
          case Left(error) => errors += error
          case Right(knownModules) =>
            moduleBindings(graph, knownModules.map(_.entity).toSet).left.foreach(errors += _)
            moduleImports(graph, knownModules.map(_.entity).toSet).left.foreach(errors += _)
      }
    errors.result()

  private def productionStatementForm(graph: Graph, production: EntityId): Either[String, String] =
    for
      formEntity <- sourceEntityAt(graph, production, "statement-form")
      _ <- expectKind(graph, formEntity, "grammar.statement-form")
      formNode <- graph.entity(formEntity).toRight(s"missing statement form ${formEntity.value}")
      form <- required(formNode, "name", formEntity.value)
      _ <- if Set("simple", "compound").contains(form) then Right(()) else Left(s"unsupported statement form $form")
    yield form

  private def statementFormForTree(productions: Vector[Production], tree: Tree): Either[String, String] =
    productions.find(p => p.sort == tree.sort && p.constructor == tree.constructor)
      .map(_.statementForm)
      .toRight(s"no statement form for ${tree.constructor.value}:${tree.sort}")

  private def validateCombinatorProductions(
      graph: Graph,
      grammar: EntityId,
      label: String
  ): Vector[String] =
    val errors = Vector.newBuilder[String]
    val prodEntities = graph.entity(grammar).toVector.flatMap(_.ports)
      .filter(p => p.direction == Direction.In && p.name.startsWith("p"))
      .flatMap(p => sourceEntityAt(graph, grammar, p.name).toOption)
    prodEntities.foreach { prodEntity =>
      graph.entity(prodEntity).foreach { prodNode =>
        if prodNode.attrs.contains("pattern") then errors += s"${prodEntity.value} embeds a pattern string under $label"
        if sourceEntityAt(graph, prodEntity, "body").isLeft then errors += s"${prodEntity.value} lacks a $label body"
        if sourceEntityAt(graph, prodEntity, "precedence").isLeft then errors += s"${prodEntity.value} lacks graph precedence"
        if sourceEntityAt(graph, prodEntity, "associativity").isLeft then errors += s"${prodEntity.value} lacks graph associativity"
      }
    }
    errors.result()

  private def insertUtf8(source: String, atByte: Int, inserted: String): Either[String, String] =
    val sourceBytes = source.getBytes(StandardCharsets.UTF_8)
    if atByte < 0 || atByte > sourceBytes.length then Left(s"invalid UTF-8 insertion offset $atByte")
    else
      val prefix = java.util.Arrays.copyOfRange(sourceBytes, 0, atByte)
      val suffix = java.util.Arrays.copyOfRange(sourceBytes, atByte, sourceBytes.length)
      val insertedBytes = inserted.getBytes(StandardCharsets.UTF_8)
      val out = new Array[Byte](prefix.length + insertedBytes.length + suffix.length)
      System.arraycopy(prefix, 0, out, 0, prefix.length)
      System.arraycopy(insertedBytes, 0, out, prefix.length, insertedBytes.length)
      System.arraycopy(suffix, 0, out, prefix.length + insertedBytes.length, suffix.length)
      val decoded = new String(out, StandardCharsets.UTF_8)
      if java.util.Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), out) then Right(decoded)
      else Left(s"UTF-8 insertion offset $atByte splits a code point")

  private def grammarEncoding(graph: Graph, spec: LanguageSpec): Either[String, String] =
    graph.entity(spec.grammar) match
      case Some(node) =>
        val unique = node.attrs.get("ambiguity").contains("unique")
        val normalization = node.attrs.get("normalization")
        if !unique then Left(s"${spec.grammar.value} requests unsupported grammar ambiguity policy")
        else
          val tokenizer = node.attrs.get("tokenizer")
          (tokenizer, node.attrs.get("parser"), node.attrs.get("printer"), node.attrs.get("expression-model"), node.attrs.get("lexer-model")) match
            case (Some("whitespace"), Some("prefix-recursive"), Some("production-template"), _, _) if normalization.contains("single-space") => Right("template")
            case (Some("whitespace"), Some("prefix-recursive"), Some("expression-graph"), Some("first-class"), _) if normalization.contains("single-space") => Right("expression-graph")
            case (Some("whitespace"), Some("precedence-climbing"), Some("combinator-graph"), Some("combinators-v2"), _) if normalization.contains("single-space") => Right("combinator-graph")
            case (Some("whitespace"), Some("precedence-climbing"), Some("capture-graph"), Some("cardinality-v3"), _) if normalization.contains("single-space") => Right("capture-graph")
            case (Some("graph-lexer"), Some("precedence-climbing"), Some("capture-graph"), Some("cardinality-v3"), Some("first-class-v4")) if normalization.contains("single-space") => Right("lexer-graph")
            case (Some("graph-lexer"), Some("precedence-climbing"), Some("token-graph"), Some("lexical-v5"), Some("expression-v5")) if normalization.contains("single-space") => Right("lexical-graph")
            case (Some("graph-lexer"), Some("precedence-climbing"), Some("token-graph"), Some("lexical-v6"), Some("codec-v6")) if normalization.contains("single-space") => Right("lexical-codec-graph")
            case (Some("graph-lexer"), Some("precedence-climbing"), Some("token-graph"), Some("lexical-v7"), Some("codec-v6"))
                if normalization.contains("single-space") && node.attrs.get("source-model").contains("provenance-v7") => Right("lexical-source-graph")
            case (Some("graph-lexer"), Some("precedence-climbing"), Some("token-graph"), Some("lexical-v8"), Some("codec-v6"))
                if normalization.contains("single-space") &&
                  node.attrs.get("source-model").contains("provenance-v7") &&
                  node.attrs.get("source-map-model").contains("ast-v8") &&
                  node.attrs.get("layout-model").contains("offside-v8") => Right("lexical-source-layout-graph")
            case (Some("graph-lexer"), Some("layout-precedence-climbing"), Some("layout-token-graph"), Some("lexical-v9"), Some("codec-v6"))
                if normalization.contains("layout-canonical") &&
                  node.attrs.get("source-model").contains("provenance-v7") &&
                  node.attrs.get("source-map-model").contains("ast-v8") &&
                  node.attrs.get("layout-model").contains("consumed-v9") => Right("layout-grammar-graph")
            case (Some("graph-lexer"), Some("suite-precedence-climbing"), Some("suite-graph"), Some("lexical-v10"), Some("codec-v6"))
                if normalization.contains("layout-canonical") &&
                  node.attrs.get("source-model").contains("provenance-v7") &&
                  node.attrs.get("source-map-model").contains("ast-v8") &&
                  node.attrs.get("layout-model").contains("suites-v10") => Right("suite-grammar-graph")
            case (Some("graph-lexer"), Some("recoverable-suite-precedence-climbing"), Some("recoverable-suite-graph"), Some("lexical-v11"), Some("codec-v6"))
                if normalization.contains("layout-canonical") &&
                  node.attrs.get("source-model").contains("provenance-v7") &&
                  node.attrs.get("source-map-model").contains("ast-v8") &&
                  node.attrs.get("layout-model").contains("suites-v10") &&
                  node.attrs.get("recovery-model").contains("holes-v11") &&
                  node.attrs.get("diagnostic-model").contains("source-v11") => Right("recovery-grammar-graph")
            case (tok, parser, printer, model, lexer) => Left(s"${spec.grammar.value} requests unsupported grammar policy $tok/$parser/$printer/$model/$lexer")
      case None => Left(s"missing grammar ${spec.grammar.value}")

  private def grammarPolicy(graph: Graph, spec: LanguageSpec): Either[String, Unit] =
    grammarEncoding(graph, spec).map(_ => ())

  private def parseSort(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      sort: String,
      at: Int,
      depth: Int
  ): Vector[(Tree, Int)] =
    if depth > tokens.size + 8 then Vector.empty
    else
      productions.filter(_.sort == sort).flatMap { prod =>
        parseItems(productions, tokens, prod.pattern, at, depth, Vector.empty).map { case (fields, next) =>
          Tree(sort, prod.constructor, fields) -> next
        }
      }

  private def parseItems(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      items: Vector[PatternItem],
      at: Int,
      depth: Int,
      fields: Vector[(String, Tree)]
  ): Vector[(Vector[(String, Tree)], Int)] =
    items.headOption match
      case None => Vector(fields -> at)
      case Some(PatternItem.Literal(value)) =>
        if tokens.lift(at).exists(token => token.rule.isEmpty && token.text == value) then parseItems(productions, tokens, items.tail, at + 1, depth, fields)
        else Vector.empty
      case Some(PatternItem.Field(name, sort)) =>
        parseSort(productions, tokens, sort, at, depth + 1).flatMap { case (tree, next) =>
          parseItems(productions, tokens, items.tail, next, depth, fields :+ (name -> tree))
        }

  private def parseSortPrecedence(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      sort: String,
      at: Int,
      minPrecedence: Int,
      depth: Int
  ): Vector[(Tree, Int)] =
    if depth > tokens.size + 16 then Vector.empty
    else
      val bases = productions.filter(p => p.sort == sort && p.fixity != "infix" && p.precedence >= minPrecedence).flatMap { prod =>
        prod.body.toVector.flatMap { body =>
          val sameSortMin = if prod.fixity == "prefix" then prod.precedence else 0
          parseGrammarExpr(productions, tokens, body, at, depth + 1, sort, sameSortMin).map { case (values, next) =>
            treeFromValues(sort, prod.constructor, values) -> next
          }
        }
      }
      bases.flatMap { case (tree, next) =>
        parseInfixTail(productions, tokens, sort, tree, next, minPrecedence, depth + 1)
      }.distinct

  private def parseInfixTail(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      sort: String,
      leftTree: Tree,
      at: Int,
      minPrecedence: Int,
      depth: Int
  ): Vector[(Tree, Int)] =
    if depth > tokens.size + 16 then Vector(leftTree -> at)
    else
      val continued = productions.filter { p =>
        p.sort == sort && p.fixity == "infix" && p.precedence >= minPrecedence
      }.flatMap { prod =>
        prod.body.toVector.flatMap { body =>
          infixShape(body, sort).toVector.flatMap { case (leftField, operator, rightField) =>
            parseGrammarExpr(productions, tokens, operator, at, depth + 1, sort, Int.MaxValue).flatMap { case (operatorFields, afterOperator) =>
              if operatorFields.nonEmpty then Vector.empty
              else
                val rhsMin = if prod.associativity == "right" then prod.precedence else prod.precedence + 1
                parseSortPrecedence(productions, tokens, sort, afterOperator, rhsMin, depth + 1).flatMap { case (rightTree, afterRight) =>
                  val combined = Tree(sort, prod.constructor, Vector(leftField -> leftTree, rightField -> rightTree))
                  parseInfixTail(productions, tokens, sort, combined, afterRight, minPrecedence, depth + 1)
                }
            }
          }
        }
      }
      (Vector(leftTree -> at) ++ continued).distinct

  private def infixShape(expr: GrammarExpr, sort: String): Option[(String, GrammarExpr, String)] = expr match
    case GrammarExpr.Sequence(Vector(
          GrammarExpr.Reference(leftField, leftSort),
          operator,
          GrammarExpr.Reference(rightField, rightSort)
        )) if leftSort == sort && rightSort == sort && captureSignature(operator).isEmpty =>
      Some((leftField, operator, rightField))
    case _ => None

  private def parseGrammarExpr(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      expr: GrammarExpr,
      at: Int,
      depth: Int,
      currentSort: String,
      sameSortMinPrecedence: Int
  ): Vector[(Vector[(String, FieldValue)], Int)] =
    if depth > tokens.size + 16 then Vector.empty
    else expr match
      case GrammarExpr.Literal(value) =>
        if tokens.lift(at).exists(token => token.rule.isEmpty && token.text == value) then Vector(Vector.empty -> (at + 1)) else Vector.empty
      case GrammarExpr.Token(field, sort, rule, constructor) =>
        tokens.lift(at) match
          case Some(token) if token.rule.contains(rule) =>
            val value = token.value match
              case Some(decoded) => FieldValue.Value(sort, constructor, decoded)
              case None => FieldValue.Lexeme(sort, constructor, token.text)
            Vector(Vector(field -> value) -> (at + 1))
          case _ => Vector.empty
      case GrammarExpr.LayoutToken(tokenEntity) =>
        if tokens.lift(at).exists(_.layout.contains(tokenEntity)) then Vector(Vector.empty -> (at + 1)) else Vector.empty
      case GrammarExpr.Hole(surface, _) =>
        if tokens.lift(at).exists(token => token.rule.isEmpty && token.text == surface) then Vector(Vector.empty -> (at + 1)) else Vector.empty
      case GrammarExpr.Suite(field, sort, min, max, newline, indent, dedent) =>
        if !tokens.lift(at).exists(_.layout.contains(indent)) then Vector.empty
        else
          def loop(count: Int, position: Int, values: Vector[Tree]): Vector[(Vector[Tree], Int)] =
            if tokens.lift(position).exists(_.layout.contains(dedent)) then
              if count >= min then Vector(values -> (position + 1)) else Vector.empty
            else if count >= max then Vector.empty
            else
              parseSortPrecedence(productions, tokens, sort, position, 0, depth + count + 1).flatMap { case (tree, next) =>
                statementFormForTree(productions, tree) match
                  case Right("simple") if tokens.lift(next).exists(_.layout.contains(newline)) => loop(count + 1, next + 1, values :+ tree)
                  case Right("compound") => loop(count + 1, next, values :+ tree)
                  case _ => Vector.empty
              }
          loop(0, at + 1, Vector.empty).distinct.map { case (values, next) => Vector(field -> FieldValue.Many(values)) -> next }
      case GrammarExpr.Reference(field, sort) =>
        val min = if sort == currentSort then sameSortMinPrecedence else 0
        parseSortPrecedence(productions, tokens, sort, at, min, depth + 1).map { case (tree, next) =>
          Vector(field -> FieldValue.One(tree)) -> next
        }
      case GrammarExpr.Sequence(items) =>
        parseGrammarSequence(productions, tokens, items, at, depth + 1, currentSort, sameSortMinPrecedence, Vector.empty)
      case GrammarExpr.Choice(alternatives, _) =>
        alternatives.flatMap(alt => parseGrammarExpr(productions, tokens, alt, at, depth + 1, currentSort, sameSortMinPrecedence)).distinct
      case GrammarExpr.Optional(body, _) =>
        (Vector(Vector.empty[(String, FieldValue)] -> at) ++
          parseGrammarExpr(productions, tokens, body, at, depth + 1, currentSort, sameSortMinPrecedence)).distinct
      case GrammarExpr.Repeat(body, min, max, _) =>
        def loop(count: Int, position: Int): Vector[Int] =
          val stop = if count >= min then Vector(position) else Vector.empty
          if count >= max then stop
          else
            val next = parseGrammarExpr(productions, tokens, body, position, depth + count + 1, currentSort, sameSortMinPrecedence)
              .collect { case (values, after) if values.isEmpty && after > position => after }
              .distinct
            stop ++ next.flatMap(after => loop(count + 1, after))
        loop(0, at).distinct.map(position => Vector.empty[(String, FieldValue)] -> position)
      case GrammarExpr.Capture(field, sort, cardinality, body, min, max) =>
        def oneAt(position: Int, extraDepth: Int): Vector[(Tree, Int)] =
          parseGrammarExpr(productions, tokens, body, position, depth + extraDepth, currentSort, 0).flatMap { case (values, next) =>
            singleCapturedTree(values).toVector.collect { case tree if tree.sort == sort && next > position => tree -> next }
          }
        cardinality match
          case Cardinality.One =>
            oneAt(at, 1).map { case (tree, next) => Vector(field -> FieldValue.One(tree)) -> next }
          case Cardinality.Optional =>
            val absent = Vector(Vector(field -> FieldValue.Optional(None)) -> at)
            val present = oneAt(at, 1).map { case (tree, next) => Vector(field -> FieldValue.Optional(Some(tree))) -> next }
            (absent ++ present).distinct
          case Cardinality.Many =>
            def loop(count: Int, position: Int, values: Vector[Tree]): Vector[(Vector[Tree], Int)] =
              val stop = if count >= min then Vector(values -> position) else Vector.empty
              if count >= max then stop
              else stop ++ oneAt(position, count + 1).flatMap { case (tree, next) => loop(count + 1, next, values :+ tree) }
            loop(0, at, Vector.empty).distinct.map { case (values, next) => Vector(field -> FieldValue.Many(values)) -> next }

  private def parseGrammarSequence(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      items: Vector[GrammarExpr],
      at: Int,
      depth: Int,
      currentSort: String,
      sameSortMinPrecedence: Int,
      fields: Vector[(String, FieldValue)]
  ): Vector[(Vector[(String, FieldValue)], Int)] =
    items.headOption match
      case None => Vector(fields -> at)
      case Some(head) =>
        parseGrammarExpr(productions, tokens, head, at, depth + 1, currentSort, sameSortMinPrecedence).flatMap { case (captured, next) =>
          parseGrammarSequence(productions, tokens, items.tail, next, depth + 1, currentSort, sameSortMinPrecedence, fields ++ captured)
        }

  private def singleCapturedTree(values: Vector[(String, FieldValue)]): Option[Tree] = values match
    case Vector((_, FieldValue.One(tree))) => Some(tree)
    case _ => None

  private def treeFromValues(sort: String, constructor: EntityId, values: Vector[(String, FieldValue)]): Tree =
    val scalar = values.collect { case (name, FieldValue.One(tree)) => name -> tree }
    val cardinal = values.collect {
      case pair @ (_, FieldValue.Optional(_)) => pair
      case pair @ (_, FieldValue.Many(_)) => pair
      case pair @ (_, FieldValue.Lexeme(_, _, _)) => pair
      case pair @ (_, FieldValue.Value(_, _, _)) => pair
    }
    Tree(sort, constructor, scalar, cardinal)

  private def printTree(
      graph: Graph,
      language: EntityId,
      productions: Vector[Production],
      tree: Tree
  ): Either[String, Vector[PrintPiece]] =
    productions.find(p => p.sort == tree.sort && p.constructor == tree.constructor) match
      case None => Left(s"no production for ${tree.constructor.value}:${tree.sort}")
      case Some(prod) =>
        val names = tree.fieldValues.map(_._1)
        if names.distinct.size != names.size then Left(s"duplicate AST field in ${tree.constructor.value}")
        else
          val fieldMap = tree.fieldValues.toMap
          prod.body match
            case Some(body) => printGrammarExpr(graph, language, productions, body, fieldMap)
            case None =>
              val pieces = prod.pattern.map {
                case PatternItem.Literal(value) => Right(Vector(PrintPiece.Text(value)))
                case PatternItem.Field(name, sort) =>
                  fieldMap.get(name).toRight(s"missing AST field $name for ${tree.constructor.value}").flatMap {
                    case FieldValue.One(child) if child.sort == sort => printTree(graph, language, productions, child)
                    case FieldValue.One(child) => Left(s"AST field $name has sort ${child.sort}, expected $sort")
                    case _ => Left(s"legacy AST field $name must have cardinality one")
                  }
              }
              sequence(pieces).map(_.flatten)

  private def printGrammarExpr(
      graph: Graph,
      language: EntityId,
      productions: Vector[Production],
      expr: GrammarExpr,
      fields: Map[String, FieldValue]
  ): Either[String, Vector[PrintPiece]] = expr match
    case GrammarExpr.Literal(value) => Right(Vector(PrintPiece.Text(value)))
    case GrammarExpr.Token(field, sort, rule, constructor) =>
      fields.get(field).toRight(s"missing AST field $field").flatMap {
        case FieldValue.Lexeme(actualSort, actualConstructor, text) if actualSort == sort && actualConstructor == constructor => Right(Vector(PrintPiece.Text(text)))
        case FieldValue.Lexeme(actualSort, actualConstructor, _) => Left(s"AST field $field is $actualSort/${actualConstructor.value}, expected $sort/${constructor.value}")
        case FieldValue.Value(actualSort, actualConstructor, value) if actualSort == sort && actualConstructor == constructor =>
          decodeLexerRule(graph, language, rule).flatMap {
            case LexerRule(_, _, _, _, _, _, Some(codec)) => encodeLexicalCodec(codec, value).map(text => Vector(PrintPiece.Text(text)))
            case _ => Left(s"AST field $field carries a decoded lexical value but $rule has no codec")
          }
        case FieldValue.Value(actualSort, actualConstructor, _) => Left(s"AST field $field is $actualSort/${actualConstructor.value}, expected $sort/${constructor.value}")
        case other => Left(s"AST field $field is not a lexical token: $other")
      }
    case GrammarExpr.LayoutToken(tokenEntity) => Right(Vector(PrintPiece.Layout(tokenEntity)))
    case GrammarExpr.Hole(surface, _) => Right(Vector(PrintPiece.Text(surface)))
    case GrammarExpr.Suite(field, sort, min, max, newline, indent, dedent) =>
      fields.get(field).toRight(s"missing AST field $field").flatMap {
        case FieldValue.Many(values) if values.size >= min && values.size <= max && values.forall(_.sort == sort) =>
          sequence(values.map { child =>
            for
              rendered <- printTree(graph, language, productions, child)
              form <- statementFormForTree(productions, child)
              terminated <- (form match
                case "simple" => Right(rendered :+ PrintPiece.Layout(newline))
                case "compound" => Right(rendered)
                case other => Left(s"unsupported statement form $other for ${child.constructor.value}"))
            yield terminated
          }).map(parts => Vector(PrintPiece.Layout(indent)) ++ parts.flatten ++ Vector(PrintPiece.Layout(dedent)))
        case FieldValue.Many(values) => Left(s"AST field $field has invalid suite cardinality/sort: $values")
        case other => Left(s"AST field $field is not a suite collection: $other")
      }
    case GrammarExpr.Reference(field, sort) =>
      fields.get(field).toRight(s"missing AST field $field").flatMap {
        case FieldValue.One(child) if child.sort == sort => printTree(graph, language, productions, child)
        case FieldValue.One(child) => Left(s"AST field $field has sort ${child.sort}, expected $sort")
        case _ => Left(s"AST field $field is not cardinality one")
      }
    case GrammarExpr.Sequence(items) =>
      sequence(items.map(item => printGrammarExpr(graph, language, productions, item, fields))).map(_.flatten)
    case GrammarExpr.Choice(alternatives, printAlternative) =>
      alternatives.lift(printAlternative - 1).toRight(s"invalid canonical choice alternative $printAlternative").flatMap { selected =>
        printGrammarExpr(graph, language, productions, selected, fields)
      }
    case GrammarExpr.Optional(body, printIncluded) =>
      if printIncluded then printGrammarExpr(graph, language, productions, body, fields) else Right(Vector.empty)
    case GrammarExpr.Repeat(body, _, _, printCount) =>
      sequence(Vector.fill(printCount)(printGrammarExpr(graph, language, productions, body, fields))).map(_.flatten)
    case GrammarExpr.Capture(field, sort, cardinality, body, _, _) =>
      captureSignature(body) match
        case Vector(FieldSpec(innerField, innerSort, Cardinality.One)) if innerSort == sort =>
          def renderOne(child: Tree): Either[String, Vector[PrintPiece]] =
            if child.sort != sort then Left(s"AST field $field has sort ${child.sort}, expected $sort")
            else printGrammarExpr(graph, language, productions, body, Map(innerField -> FieldValue.One(child)))
          fields.get(field).toRight(s"missing AST field $field").flatMap {
            case FieldValue.One(child) if cardinality == Cardinality.One => renderOne(child)
            case FieldValue.Optional(None) if cardinality == Cardinality.Optional => Right(Vector.empty)
            case FieldValue.Optional(Some(child)) if cardinality == Cardinality.Optional => renderOne(child)
            case FieldValue.Many(values) if cardinality == Cardinality.Many => sequence(values.map(renderOne)).map(_.flatten)
            case other => Left(s"AST field $field does not match grammar cardinality $cardinality: $other")
          }
        case signature => Left(s"capture body for $field must expose exactly one scalar field, found $signature")

  private def decodeGrammarExpr(
      graph: Graph,
      language: EntityId,
      entity: EntityId,
      visiting: Set[EntityId]
  ): Either[String, GrammarExpr] =
    if visiting.contains(entity) then Left(s"cyclic grammar expression at ${entity.value}")
    else
      graph.entity(entity) match
        case None => Left(s"missing grammar expression ${entity.value}")
        case Some(node) if node.attrs.get("language") != Some(language.value) =>
          Left(s"grammar expression ${entity.value} is outside ${language.value}")
        case Some(node) =>
          val next = visiting + entity
          node.kind match
            case "grammar.literal" =>
              required(node, "value", entity.value).flatMap { value =>
                if value.nonEmpty && !value.exists(_.isWhitespace) then Right(GrammarExpr.Literal(value))
                else Left(s"invalid grammar literal ${entity.value}")
              }
            case "grammar.reference" =>
              for
                field <- required(node, "field", entity.value)
                sortEntity <- sourceEntityAt(graph, entity, "sort")
                _ <- expectKind(graph, sortEntity, "language.sort")
                sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
                sort <- required(sortNode, "name", sortEntity.value)
              yield GrammarExpr.Reference(field, sort)
            case "grammar.token" =>
              for
                field <- required(node, "field", entity.value)
                ruleEntity <- sourceEntityAt(graph, entity, "rule")
                rule <- decodeLexerRule(graph, language, ruleEntity)
              yield GrammarExpr.Token(field, rule.sort, rule.entity, rule.constructor)
            case "grammar.layout-token" =>
              for
                token <- sourceEntityAt(graph, entity, "token")
                _ <- expectKind(graph, token, "layout.token")
              yield GrammarExpr.LayoutToken(token)
            case "grammar.hole" =>
              for
                surface <- required(node, "surface", entity.value)
                _ <- if surface.nonEmpty && !surface.exists(_.isWhitespace) then Right(()) else Left(s"${entity.value} has invalid hole surface")
                sortEntity <- sourceEntityAt(graph, entity, "sort")
                _ <- expectKind(graph, sortEntity, "language.sort")
                sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
                sort <- required(sortNode, "name", sortEntity.value)
              yield GrammarExpr.Hole(surface, sort)
            case "grammar.suite" =>
              for
                field <- required(node, "field", entity.value)
                sortEntity <- sourceEntityAt(graph, entity, "sort")
                _ <- expectKind(graph, sortEntity, "language.sort")
                sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
                sort <- required(sortNode, "name", sortEntity.value)
                cardinalityEntity <- sourceEntityAt(graph, entity, "cardinality")
                _ <- expectKind(graph, cardinalityEntity, "grammar.cardinality")
                cardinalityNode <- graph.entity(cardinalityEntity).toRight(s"missing cardinality ${cardinalityEntity.value}")
                cardinality <- required(cardinalityNode, "value", cardinalityEntity.value)
                _ <- if cardinality == "many" then Right(()) else Left(s"${entity.value} suite cardinality must be many")
                min <- node.attrs.get("min").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer min")
                max <- node.attrs.get("max").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max")
                _ <- if min >= 1 && max >= min then Right(()) else Left(s"${entity.value} has invalid suite bounds")
                newline <- sourceEntityAt(graph, entity, "newline")
                indent <- sourceEntityAt(graph, entity, "indent")
                dedent <- sourceEntityAt(graph, entity, "dedent")
                _ <- sequence(Vector(newline, indent, dedent).map(token => expectKind(graph, token, "layout.token")))
              yield GrammarExpr.Suite(field, sort, min, max, newline, indent, dedent)
            case "grammar.sequence" =>
              orderedExpressionChildren(graph, entity, node, "item").flatMap { children =>
                sequence(children.map(child => decodeGrammarExpr(graph, language, child, next))).map(GrammarExpr.Sequence.apply)
              }
            case "grammar.choice" =>
              for
                children <- orderedExpressionChildren(graph, entity, node, "alt")
                alternatives <- sequence(children.map(child => decodeGrammarExpr(graph, language, child, next)))
                printAlternative <- node.attrs.get("print-alt").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer print-alt")
                _ <- if printAlternative >= 1 && printAlternative <= alternatives.size then Right(()) else Left(s"${entity.value} print-alt is outside its alternatives")
                signatures = alternatives.map(captureSignature)
                _ <- if signatures.distinct.size == 1 then Right(()) else Left(s"${entity.value} alternatives capture different AST fields")
              yield GrammarExpr.Choice(alternatives, printAlternative)
            case "grammar.optional" =>
              for
                child <- sourceEntityAt(graph, entity, "body")
                body <- decodeGrammarExpr(graph, language, child, next)
                _ <- if captureSignature(body).isEmpty then Right(()) else Left(s"${entity.value} optional captures are not supported in GrammarCombinators")
                printMode <- required(node, "print", entity.value)
                printIncluded <- printMode match
                  case "omit" => Right(false)
                  case "include" => Right(true)
                  case other => Left(s"${entity.value} has invalid optional print mode $other")
              yield GrammarExpr.Optional(body, printIncluded)
            case "grammar.repeat" =>
              for
                child <- sourceEntityAt(graph, entity, "body")
                body <- decodeGrammarExpr(graph, language, child, next)
                _ <- if captureSignature(body).isEmpty then Right(()) else Left(s"${entity.value} syntactic repeat must be capture-free")
                min <- node.attrs.get("min").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer min")
                max <- node.attrs.get("max").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max")
                printCount <- node.attrs.get("print-count").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer print-count")
                _ <- if min >= 0 && max >= min && printCount >= min && printCount <= max then Right(()) else Left(s"${entity.value} has invalid repetition bounds")
              yield GrammarExpr.Repeat(body, min, max, printCount)
            case "grammar.capture" =>
              for
                field <- required(node, "field", entity.value)
                bodyEntity <- sourceEntityAt(graph, entity, "body")
                body <- decodeGrammarExpr(graph, language, bodyEntity, next)
                sortEntity <- sourceEntityAt(graph, entity, "sort")
                _ <- expectKind(graph, sortEntity, "language.sort")
                sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
                sort <- required(sortNode, "name", sortEntity.value)
                cardinalityEntity <- sourceEntityAt(graph, entity, "cardinality")
                _ <- expectKind(graph, cardinalityEntity, "grammar.cardinality")
                cardinalityNode <- graph.entity(cardinalityEntity).toRight(s"missing cardinality ${cardinalityEntity.value}")
                cardinalityText <- required(cardinalityNode, "value", cardinalityEntity.value)
                cardinality <- decodeCardinality(cardinalityText)
                min <- node.attrs.get("min").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer min")
                max <- node.attrs.get("max").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max")
                _ <- validateCaptureBounds(entity, cardinality, min, max)
                bodySignature = captureSignature(body)
                _ <- bodySignature match
                  case Vector(FieldSpec(_, bodySort, Cardinality.One)) if bodySort == sort => Right(())
                  case _ => Left(s"${entity.value} body must expose exactly one scalar $sort value")
              yield GrammarExpr.Capture(field, sort, cardinality, body, min, max)
            case other => Left(s"${entity.value} has unsupported grammar expression kind $other")

  private def orderedExpressionChildren(
      graph: Graph,
      entity: EntityId,
      node: Node,
      prefix: String
  ): Either[String, Vector[EntityId]] =
    val ports = node.ports.filter(p => p.direction == Direction.In && p.name.startsWith(prefix)).sortBy(_.name)
    val expected = Vector.tabulate(ports.size)(i => prefix + f"${i + 1}%02d")
    if ports.isEmpty then Left(s"empty grammar $prefix collection ${entity.value}")
    else if ports.map(_.name) != expected then Left(s"non-canonical grammar $prefix ports on ${entity.value}")
    else sequence(ports.map(port => sourceEntityAt(graph, entity, port.name)))

  private def flattenSimpleExpression(expr: GrammarExpr): Either[String, Vector[PatternItem]] = expr match
    case GrammarExpr.Literal(value) => Right(Vector(PatternItem.Literal(value)))
    case GrammarExpr.Reference(field, sort) => Right(Vector(PatternItem.Field(field, sort)))
    case GrammarExpr.Sequence(items) => sequence(items.map(flattenSimpleExpression)).map(_.flatten)
    case _ => Left("ExpressionGrammar accepts only literal/reference/sequence expressions")

  private def captureSignature(expr: GrammarExpr): Vector[FieldSpec] = expr match
    case GrammarExpr.Literal(_) => Vector.empty
    case GrammarExpr.Reference(field, sort) => Vector(FieldSpec(field, sort, Cardinality.One))
    case GrammarExpr.Sequence(items) => items.flatMap(captureSignature)
    case GrammarExpr.Choice(alternatives, _) => alternatives.headOption.map(captureSignature).getOrElse(Vector.empty)
    case GrammarExpr.Optional(_, _) => Vector.empty
    case GrammarExpr.Repeat(_, _, _, _) => Vector.empty
    case GrammarExpr.Capture(field, sort, cardinality, _, _, _) => Vector(FieldSpec(field, sort, cardinality))
    case GrammarExpr.Token(field, sort, _, _) => Vector(FieldSpec(field, sort, Cardinality.One))
    case GrammarExpr.LayoutToken(_) => Vector.empty
    case GrammarExpr.Suite(field, sort, _, _, _, _, _) => Vector(FieldSpec(field, sort, Cardinality.Many))
    case GrammarExpr.Hole(_, _) => Vector.empty

  private def decodeCardinality(value: String): Either[String, Cardinality] = value match
    case "one" => Right(Cardinality.One)
    case "optional" => Right(Cardinality.Optional)
    case "many" => Right(Cardinality.Many)
    case other => Left(s"unknown grammar cardinality $other")

  private def validateCaptureBounds(entity: EntityId, cardinality: Cardinality, min: Int, max: Int): Either[String, Unit] =
    cardinality match
      case Cardinality.One if min == 1 && max == 1 => Right(())
      case Cardinality.Optional if min == 0 && max == 1 => Right(())
      case Cardinality.Many if min >= 0 && max >= min => Right(())
      case _ => Left(s"${entity.value} has bounds $min..$max incompatible with $cardinality")

  private def productionPrecedence(graph: Graph, production: EntityId): Either[String, Int] =
    for
      entity <- sourceEntityAt(graph, production, "precedence")
      _ <- expectKind(graph, entity, "grammar.precedence")
      node <- graph.entity(entity).toRight(s"missing precedence ${entity.value}")
      level <- node.attrs.get("level").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer level")
      _ <- if level >= 0 then Right(()) else Left(s"${entity.value} has negative precedence")
    yield level

  private def productionAssociativity(graph: Graph, production: EntityId): Either[String, String] =
    for
      entity <- sourceEntityAt(graph, production, "associativity")
      _ <- expectKind(graph, entity, "grammar.associativity")
      node <- graph.entity(entity).toRight(s"missing associativity ${entity.value}")
      value <- required(node, "value", entity.value)
      _ <- if Set("none", "left", "right").contains(value) then Right(()) else Left(s"${entity.value} has invalid associativity $value")
    yield value

  private def decodeConstructor(graph: Graph, entity: EntityId, node: Node): Either[String, ConstructorSpec] =
    for
      name <- required(node, "name", entity.value)
      sort <- required(node, "sort", entity.value)
      fields <-
        if node.attrs.contains("fields") then decodeFields(node.attrs("fields"))
        else decodeFirstClassFields(graph, entity, node)
    yield ConstructorSpec(entity, name, sort, fields)

  private def decodeFirstClassFields(graph: Graph, constructor: EntityId, node: Node): Either[String, Vector[FieldSpec]] =
    val ports = node.ports.filter(p => p.direction == Direction.In && p.name.startsWith("f")).sortBy(_.name)
    val expected = Vector.tabulate(ports.size)(i => f"f${i + 1}%02d")
    if ports.map(_.name) != expected then Left(s"${constructor.value} has non-canonical constructor field ports")
    else sequence(ports.map { port =>
      for
        fieldEntity <- sourceEntityAt(graph, constructor, port.name)
        _ <- expectKind(graph, fieldEntity, "language.constructor-field")
        fieldNode <- graph.entity(fieldEntity).toRight(s"missing constructor field ${fieldEntity.value}")
        fieldName <- required(fieldNode, "name", fieldEntity.value)
        owner <- required(fieldNode, "owner", fieldEntity.value)
        _ <- if owner == constructor.value then Right(()) else Left(s"${fieldEntity.value} belongs to $owner, not ${constructor.value}")
        sortEntity <- sourceEntityAt(graph, fieldEntity, "sort")
        _ <- expectKind(graph, sortEntity, "language.sort")
        sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
        sort <- required(sortNode, "name", sortEntity.value)
        cardinalityEntity <- sourceEntityAt(graph, fieldEntity, "cardinality")
        _ <- expectKind(graph, cardinalityEntity, "grammar.cardinality")
        cardinalityNode <- graph.entity(cardinalityEntity).toRight(s"missing cardinality ${cardinalityEntity.value}")
        cardinalityText <- required(cardinalityNode, "value", cardinalityEntity.value)
        cardinality <- decodeCardinality(cardinalityText)
      yield FieldSpec(fieldName, sort, cardinality)
    }).flatMap { fields =>
      if fields.map(_.name).distinct.size == fields.size then Right(fields)
      else Left(s"${constructor.value} contains duplicate constructor field names")
    }

  private def decodeFields(raw: String): Either[String, Vector[FieldSpec]] =
    if raw.isEmpty then Right(Vector.empty)
    else
      sequence(raw.split(';').toVector.map { field =>
        field.split(":", -1).toVector match
          case Vector(name, sort) if name.nonEmpty && sort.nonEmpty => Right(FieldSpec(name, sort, Cardinality.One))
          case _ => Left(s"invalid constructor field $field")
      }).flatMap { fields =>
        if fields.map(_.name).distinct.size == fields.size then Right(fields)
        else Left("duplicate constructor field name")
      }

  private def decodePattern(raw: String): Either[String, Vector[PatternItem]] =
    val pieces = raw.split(';').toVector.filter(_.nonEmpty)
    if pieces.isEmpty then Left("empty TemplateGrammar pattern")
    else sequence(pieces.map {
      case piece if piece.startsWith("lit:") =>
        val value = piece.stripPrefix("lit:")
        if value.nonEmpty && !value.exists(_.isWhitespace) then Right(PatternItem.Literal(value))
        else Left(s"invalid TemplateGrammar literal $piece")
      case piece if piece.startsWith("ref:") =>
        piece.split(":", -1).toVector match
          case Vector("ref", name, sort) if name.nonEmpty && sort.nonEmpty => Right(PatternItem.Field(name, sort))
          case _ => Left(s"invalid TemplateGrammar reference $piece")
      case piece => Left(s"unknown TemplateGrammar pattern item $piece")
    })

  private def operatorFor(graph: Graph, language: EntityId, primitive: String): Either[String, ChangeOperator] =
    changeOperators(graph, language).flatMap { operators =>
      operators.find(_.primitive == primitive).toRight(s"${language.value} change language lacks $primitive")
    }

  private def validateOperationVocabulary(policy: ChangeLanguageSpec, change: Change): Either[String, Unit] =
    val used = change.operations.map {
      case Op.AddNode(_) => "add-node"
      case Op.BindEntity(_, _) => "bind-entity"
      case Op.ReplaceEntity(_, _) => "replace-entity"
      case Op.RemoveEntity(_) => "remove-entity"
      case Op.Connect(_) => "connect"
      case Op.Disconnect(_) => "disconnect"
      case Op.AddRoot(_, _) => "add-root"
      case Op.RemoveRoot(_) => "remove-root"
      case Op.RefineHole(_, _) => "refine-hole"
    }.toSet
    val forbidden = used -- policy.allowedOps
    if forbidden.isEmpty then Right(()) else Left(s"change uses operators outside ${policy.entity.value}: ${forbidden.toVector.sorted.mkString(",")}")

  private def validateOwnership(before: Graph, after: Graph, language: EntityId, change: Change): Either[String, Unit] =
    def ownedNode(graph: Graph, id: ContentId): Boolean =
      graph.entities.get(language).contains(id) || graph.nodes.get(id).flatMap(_.attrs.get("language")).contains(language.value)

    def sharedSchemaNode(graph: Graph, id: ContentId): Boolean =
      graph.nodes.get(id).exists(node => Set(
        "language.schema", "grammar.schema", "lexer.schema", "source.schema", "layout.schema",
        "language.component", "grammar.component", "lexer.component", "source.component", "layout.component",
        "source.offset-unit", "source.trivia-mode", "source.attachment", "source.reconstruction", "layout.token",
        "grammar.statement-form"
      ).contains(node.kind))

    def ownedEdge(graph: Graph, edge: Edge): Boolean =
      (ownedNode(graph, edge.from.node) && (ownedNode(graph, edge.to.node) || sharedSchemaNode(graph, edge.to.node))) ||
        (sharedSchemaNode(graph, edge.from.node) && ownedNode(graph, edge.to.node))

    val checks = change.operations.map {
      case Op.AddNode(node) =>
        if node.attrs.get("language").contains(language.value) then Right(()) else Left("added node is outside selected language")
      case Op.BindEntity(_, node) =>
        if ownedNode(after, node) then Right(()) else Left("bound entity is outside selected language")
      case Op.ReplaceEntity(target, node) =>
        val oldOwned = before.entities.get(target).exists(id => ownedNode(before, id))
        if oldOwned && node.attrs.get("language").contains(language.value) then Right(()) else Left(s"replacement ${target.value} crosses language boundary")
      case Op.RemoveEntity(target) =>
        if before.entities.get(target).exists(id => ownedNode(before, id)) then Right(()) else Left(s"removal ${target.value} crosses language boundary")
      case Op.Connect(edge) =>
        if ownedEdge(after, edge) then Right(()) else Left("connected edge crosses language boundary")
      case Op.Disconnect(edgeId) =>
        before.edges.get(edgeId) match
          case Some(edge) if ownedEdge(before, edge) => Right(())
          case Some(_) => Left("disconnected edge crosses language boundary")
          case None => Left(s"disconnect references unknown edge ${edgeId.value}")
      case _ => Left("root and hole operations are not admitted by DeltaLanguageBootstrap")
    }
    sequence(checks).map(_ => ())

  private def sourceEntityAt(graph: Graph, target: EntityId, port: String): Either[String, EntityId] =
    for
      targetId <- graph.entities.get(target).toRight(s"unknown target entity ${target.value}")
      incoming = graph.incoming(PortRef(targetId, port))
      edge <- incoming match
        case Vector(one) => Right(one._2)
        case Vector() => Left(s"${target.value}.$port has no producer")
        case many => Left(s"${target.value}.$port has ${many.size} producers")
      entities = graph.entities.toVector.collect { case (entity, id) if id == edge.from.node => entity }.sortBy(_.value)
      source <- entities.headOption.toRight(s"source of ${target.value}.$port has no entity identity")
    yield source

  private def expectKind(graph: Graph, entity: EntityId, kind: String): Either[String, Unit] =
    graph.entity(entity) match
      case Some(node) if node.kind == kind => Right(())
      case Some(node) => Left(s"${entity.value} is ${node.kind}, expected $kind")
      case None => Left(s"missing ${entity.value}")

  private def required(node: Node, key: String, label: String): Either[String, String] =
    node.attrs.get(key).filter(_.nonEmpty).toRight(s"$label lacks $key")

  private def splitSet(raw: String): Set[String] = raw.split(';').iterator.map(_.trim).filter(_.nonEmpty).toSet

  private def tokenize(
      graph: Graph,
      spec: LanguageSpec,
      productions: Vector[Production],
      text: String
  ): Either[String, Vector[LexToken]] =
    graph.entity(spec.grammar).flatMap(_.attrs.get("tokenizer")) match
      case Some("whitespace") =>
        Right(text.trim.split("\\s+").toVector.filter(_.nonEmpty).map(value => LexToken(value, None)))
      case Some("graph-lexer") =>
        for
          lexer <- lexerDefinition(graph, spec)
          literals = productions.flatMap(_.body.toVector.flatMap(grammarLiterals)).distinct.sorted
          _ <- if literals.nonEmpty then Right(()) else Left(s"${lexer.entity.value} has no grammar literals to tokenize")
          rules <- if lexer.source == "grammar-literals+rules" then lexerRuleDefinitions(graph, spec) else Right(Vector.empty)
          encoding <- grammarEncoding(graph, spec)
          tokens <-
            if Set("layout-grammar-graph", "suite-grammar-graph", "recovery-grammar-graph").contains(encoding) then
              for
                policy <- layoutPolicyDefinition(graph, spec)
                document <- scanLexicalGraphDocument(text, literals, rules, lexer)
                events <- deriveLayoutEvents(text, policy, lexer.lineComment)
              yield interleaveLayoutTokens(document, events, policy)
            else if rules.isEmpty then scanGraphLexer(text, literals, lexer)
            else if graph.entity(spec.grammar).exists(node => node.attrs.get("source-model").contains("provenance-v7")) then
              scanLexicalGraphDocument(text, literals, rules, lexer).map(_.tokens.map(_.token))
            else scanLexicalGraph(text, literals, rules, lexer)
        yield tokens
      case Some(other) => Left(s"${spec.grammar.value} requests unsupported tokenizer $other")
      case None => Left(s"${spec.grammar.value} lacks tokenizer")

  private def lexerDefinition(graph: Graph, spec: LanguageSpec): Either[String, LexerSpec] =
    for
      lexerEntity <- sourceEntityAt(graph, spec.grammar, "lexer")
      lexerNode <- graph.entity(lexerEntity).toRight(s"missing lexer ${lexerEntity.value}")
      _ <- if lexerNode.kind == "lexer.definition" then Right(()) else Left(s"${lexerEntity.value} is ${lexerNode.kind}, not lexer.definition")
      _ <- if lexerNode.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${lexerEntity.value} is not owned by ${spec.entity.value}")
      _ <- if lexerNode.attrs.get("failure").contains("fail-closed") then Right(()) else Left(s"${lexerEntity.value} must fail closed")
      schema <- sourceEntityAt(graph, lexerEntity, "schema")
      version <- schema.value match
        case "lexer4.schema" => Right(4)
        case "lexer5.schema" => Right(5)
        case "lexer6.schema" => Right(6)
        case other => Left(s"${lexerEntity.value} selects unsupported lexer schema $other")
      sourceEntity <- sourceEntityAt(graph, lexerEntity, "source")
      sourceNode <- graph.entity(sourceEntity).toRight(s"missing token source ${sourceEntity.value}")
      _ <- if sourceNode.kind == "lexer.token-source" then Right(()) else Left(s"${sourceEntity.value} is ${sourceNode.kind}, not lexer.token-source")
      source <- required(sourceNode, "source", sourceEntity.value)
      _ <- if (version == 4 && source == "grammar-literals") || (Set(5, 6).contains(version) && source == "grammar-literals+rules") then Right(()) else Left(s"${sourceEntity.value} requests unsupported token source $source for Lexer$version")
      _ <- if sourceNode.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${sourceEntity.value} is not owned by ${spec.entity.value}")
      skip01 <- sourceEntityAt(graph, lexerEntity, "skip01")
      skip02 <- sourceEntityAt(graph, lexerEntity, "skip02")
      skips <- sequence(Vector(skip01, skip02).map(entity => graph.entity(entity).toRight(s"missing skip rule ${entity.value}").map(node => entity -> node)))
      _ <- if skips.forall { case (_, node) => node.kind == "lexer.skip-rule" && node.attrs.get("language").contains(spec.entity.value) } then Right(()) else Left(s"${lexerEntity.value} contains a foreign or invalid skip rule")
      whitespace <- skips.collectFirst { case (_, node) if node.kind == "lexer.skip-rule" && node.attrs.get("kind").contains("whitespace") => node.attrs.getOrElse("class", "") }.toRight(s"${lexerEntity.value} lacks whitespace skip rule")
      _ <- if whitespace == "unicode" then Right(()) else Left(s"${lexerEntity.value} requests unsupported whitespace class $whitespace")
      lineComment = skips.collectFirst { case (_, node) if node.kind == "lexer.skip-rule" && node.attrs.get("kind").contains("line-comment") => node.attrs.get("prefix").filter(_.nonEmpty) }.flatten
      selectionEntity <- sourceEntityAt(graph, lexerEntity, "selection")
      selectionNode <- graph.entity(selectionEntity).toRight(s"missing selection ${selectionEntity.value}")
      _ <- if selectionNode.kind == "lexer.selection" then Right(()) else Left(s"${selectionEntity.value} is ${selectionNode.kind}, not lexer.selection")
      _ <- if selectionNode.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${selectionEntity.value} is not owned by ${spec.entity.value}")
      selection <- required(selectionNode, "mode", selectionEntity.value)
      tieBreak <- required(selectionNode, "tie-break", selectionEntity.value)
      boundary <- required(selectionNode, "boundary", selectionEntity.value)
      _ <-
        val supported =
          (version == 4 && selection == "longest-match" && tieBreak == "lexical" && boundary == "identifier-ish") ||
            (Set(5, 6).contains(version) && selection == "longest-match" && tieBreak == "priority-then-lexical" && boundary == "rule-aware")
        if supported then Right(()) else Left(s"${selectionEntity.value} requests unsupported Lexer$version selection policy")
    yield LexerSpec(lexerEntity, source, whitespace, lineComment, selection, tieBreak, boundary, "fail-closed")

  private def recoveryPolicyDefinition(graph: Graph, spec: LanguageSpec): Either[String, RecoveryPolicy] =
    for
      entity <- sourceEntityAt(graph, spec.grammar, "recovery")
      node <- graph.entity(entity).toRight(s"missing recovery policy ${entity.value}")
      _ <- if node.kind == "grammar.recovery-policy" then Right(()) else Left(s"${entity.value} is ${node.kind}, not grammar.recovery-policy")
      _ <- if node.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${entity.value} is not owned by ${spec.entity.value}")
      maxEdits <- node.attrs.get("max-edits").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-edits")
      _ <- if maxEdits == 1 then Right(()) else Left(s"${entity.value} supports only one recovery edit")
      selection <- required(node, "selection", entity.value)
      _ <- if selection == "unique-edit" then Right(()) else Left(s"${entity.value} requests unsupported recovery selection $selection")
      candidateBoundaries <- required(node, "candidate-boundaries", entity.value)
      _ <- if candidateBoundaries == "real-token-ends" then Right(()) else Left(s"${entity.value} requests unsupported recovery boundaries $candidateBoundaries")
      failure <- required(node, "failure", entity.value)
      _ <- if failure == "strict" then Right(()) else Left(s"${entity.value} must preserve strict failure")
      strategyPorts = node.ports.filter(p => p.direction == Direction.In && p.name.startsWith("strategy")).sortBy(_.name)
      strategies <- sequence(strategyPorts.map(p => sourceEntityAt(graph, entity, p.name)))
      _ <- if strategies.nonEmpty then Right(()) else Left(s"${entity.value} has no recovery strategy")
      _ <- sequence(strategies.map(strategy => expectKind(graph, strategy, "grammar.recovery-strategy")))
      holeEntity <- sourceEntityAt(graph, entity, "hole")
      holeNode <- graph.entity(holeEntity).toRight(s"missing hole ${holeEntity.value}")
      _ <- if holeNode.kind == "grammar.hole" then Right(()) else Left(s"${holeEntity.value} is ${holeNode.kind}, not grammar.hole")
      _ <- if holeNode.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${holeEntity.value} is not owned by ${spec.entity.value}")
      surface <- required(holeNode, "surface", holeEntity.value)
      sortEntity <- sourceEntityAt(graph, holeEntity, "sort")
      _ <- expectKind(graph, sortEntity, "language.sort")
      sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
      sort <- required(sortNode, "name", sortEntity.value)
    yield RecoveryPolicy(entity, strategies, HoleSpec(holeEntity, surface, sort), maxEdits, selection, candidateBoundaries, failure)

  private def diagnosticPolicyDefinition(graph: Graph, spec: LanguageSpec): Either[String, DiagnosticPolicy] =
    for
      entity <- sourceEntityAt(graph, spec.grammar, "diagnostics")
      node <- graph.entity(entity).toRight(s"missing diagnostic policy ${entity.value}")
      _ <- if node.kind == "grammar.diagnostic-policy" then Right(()) else Left(s"${entity.value} is ${node.kind}, not grammar.diagnostic-policy")
      _ <- if node.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${entity.value} is not owned by ${spec.entity.value}")
      offsetUnit <- required(node, "offset-unit", entity.value)
      _ <- if offsetUnit == "utf8-bytes" then Right(()) else Left(s"${entity.value} requests unsupported diagnostic offset unit $offsetUnit")
      expected <- required(node, "expected", entity.value)
      _ <- if expected == "sort-name" then Right(()) else Left(s"${entity.value} requests unsupported expected-item policy $expected")
      severity <- sourceEntityAt(graph, entity, "severity")
      _ <- expectKind(graph, severity, "diagnostic.severity")
      code <- sourceEntityAt(graph, entity, "inserted-hole")
      _ <- expectKind(graph, code, "diagnostic.code")
    yield DiagnosticPolicy(entity, severity, code, offsetUnit, expected)

  private def sourcePolicyDefinition(graph: Graph, spec: LanguageSpec): Either[String, SourcePolicy] =
    for
      policyEntity <- sourceEntityAt(graph, spec.grammar, "source")
      policyNode <- graph.entity(policyEntity).toRight(s"missing source policy ${policyEntity.value}")
      _ <- if policyNode.kind == "source.policy" then Right(()) else Left(s"${policyEntity.value} is ${policyNode.kind}, not source.policy")
      _ <- if policyNode.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${policyEntity.value} is not owned by ${spec.entity.value}")
      schema <- sourceEntityAt(graph, policyEntity, "schema")
      _ <- if schema == EntityId("source7.schema") then Right(()) else Left(s"${policyEntity.value} does not select source7.schema")
      offsetEntity <- sourceEntityAt(graph, policyEntity, "offset-unit")
      triviaEntity <- sourceEntityAt(graph, policyEntity, "trivia-mode")
      attachmentEntity <- sourceEntityAt(graph, policyEntity, "attachment")
      reconstructionEntity <- sourceEntityAt(graph, policyEntity, "reconstruction")
      _ <- expectKind(graph, offsetEntity, "source.offset-unit")
      _ <- expectKind(graph, triviaEntity, "source.trivia-mode")
      _ <- expectKind(graph, attachmentEntity, "source.attachment")
      _ <- expectKind(graph, reconstructionEntity, "source.reconstruction")
      offset <- graph.entity(offsetEntity).flatMap(_.attrs.get("name")).toRight(s"${offsetEntity.value} lacks name")
      trivia <- graph.entity(triviaEntity).flatMap(_.attrs.get("name")).toRight(s"${triviaEntity.value} lacks name")
      attachment <- graph.entity(attachmentEntity).flatMap(_.attrs.get("name")).toRight(s"${attachmentEntity.value} lacks name")
      reconstruction <- graph.entity(reconstructionEntity).flatMap(_.attrs.get("name")).toRight(s"${reconstructionEntity.value} lacks name")
      _ <- if offset == "utf8-bytes" then Right(()) else Left(s"${policyEntity.value} requests unsupported source offset unit $offset")
      _ <- if trivia == "preserve" then Right(()) else Left(s"${policyEntity.value} must preserve trivia")
      _ <- if attachment == "leading" then Right(()) else Left(s"${policyEntity.value} requests unsupported trivia attachment $attachment")
      _ <- if reconstruction == "exact" then Right(()) else Left(s"${policyEntity.value} must require exact reconstruction")
      _ <- if policyNode.attrs.get("whitespace").contains("preserve") && policyNode.attrs.get("line-comments").contains("preserve") then Right(()) else Left(s"${policyEntity.value} does not preserve configured trivia")
    yield SourcePolicy(policyEntity, offset, trivia, attachment, reconstruction)

  private def sourceMapPolicyDefinition(graph: Graph, spec: LanguageSpec): Either[String, SourceMapPolicy] =
    for
      entity <- sourceEntityAt(graph, spec.grammar, "source-map")
      node <- graph.entity(entity).toRight(s"missing source map policy ${entity.value}")
      _ <- if node.kind == "source.map-policy" then Right(()) else Left(s"${entity.value} is ${node.kind}, not source.map-policy")
      _ <- if node.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${entity.value} is not owned by ${spec.entity.value}")
      schema <- sourceEntityAt(graph, entity, "schema")
      _ <- if schema == EntityId("source8.schema") then Right(()) else Left(s"${entity.value} does not select source8.schema")
      offsetEntity <- sourceEntityAt(graph, entity, "offset-unit")
      _ <- expectKind(graph, offsetEntity, "source.offset-unit")
      offset <- graph.entity(offsetEntity).flatMap(_.attrs.get("name")).toRight(s"${offsetEntity.value} lacks name")
      nodeSpan <- required(node, "node-span", entity.value)
      path <- required(node, "path", entity.value)
      failure <- required(node, "failure", entity.value)
      _ <- if offset == "utf8-bytes" && nodeSpan == "token-envelope" && path == "field-index" && failure == "fail-closed" then Right(())
           else Left(s"${entity.value} requests unsupported source-map policy")
    yield SourceMapPolicy(entity, offset, nodeSpan, path, failure)

  private def layoutPolicyDefinition(graph: Graph, spec: LanguageSpec): Either[String, LayoutPolicy] =
    for
      entity <- sourceEntityAt(graph, spec.grammar, "layout")
      node <- graph.entity(entity).toRight(s"missing layout policy ${entity.value}")
      _ <- if node.kind == "layout.policy" then Right(()) else Left(s"${entity.value} is ${node.kind}, not layout.policy")
      _ <- if node.attrs.get("language").contains(spec.entity.value) then Right(()) else Left(s"${entity.value} is not owned by ${spec.entity.value}")
      schema <- sourceEntityAt(graph, entity, "schema")
      _ <- if Set(EntityId("layout8.schema"), EntityId("layout9.schema")).contains(schema) then Right(()) else Left(s"${entity.value} does not select a supported layout schema")
      newlineToken <- sourceEntityAt(graph, entity, "newline")
      indentToken <- sourceEntityAt(graph, entity, "indent")
      dedentToken <- sourceEntityAt(graph, entity, "dedent")
      _ <- sequence(Vector(newlineToken, indentToken, dedentToken).map(e => expectKind(graph, e, "layout.token")))
      mode <- required(node, "mode", entity.value)
      indentUnit <- node.attrs.get("indent-unit").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer indent-unit")
      indentation <- required(node, "indentation", entity.value)
      tabs <- required(node, "tabs", entity.value)
      blankLines <- required(node, "blank-lines", entity.value)
      commentLines <- required(node, "comment-lines", entity.value)
      rootIndent <- node.attrs.get("root-indent").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer root-indent")
      eofDedent <- node.attrs.get("eof-dedent").flatMap(_.toBooleanOption).toRight(s"${entity.value} lacks boolean eof-dedent")
      failure <- required(node, "failure", entity.value)
      consumption = node.attrs.getOrElse("consumption", "events-only")
      streamOrder = node.attrs.getOrElse("stream-order", "not-consumed")
      virtualWidth = node.attrs.getOrElse("virtual-width", "zero")
      baseOk = mode == "offside" && indentUnit > 0 && indentation == "spaces" && tabs == "reject" &&
        blankLines == "ignore" && commentLines == "ignore" && rootIndent == 0 && eofDedent && failure == "fail-closed"
      streamOk =
        if schema == EntityId("layout9.schema") then consumption == "grammar" && streamOrder == "byte-then-event" && virtualWidth == "zero"
        else consumption == "events-only" && streamOrder == "not-consumed" && virtualWidth == "zero"
      _ <- if baseOk && streamOk then Right(()) else Left(s"${entity.value} requests unsupported layout policy")
    yield LayoutPolicy(entity, mode, indentUnit, indentation, tabs, blankLines, commentLines, rootIndent, eofDedent, failure, newlineToken, indentToken, dedentToken, consumption, streamOrder, virtualWidth)

  private def lexerRuleDefinitions(graph: Graph, spec: LanguageSpec): Either[String, Vector[LexerRule]] =
    for
      lexerEntity <- sourceEntityAt(graph, spec.grammar, "lexer")
      lexerNode <- graph.entity(lexerEntity).toRight(s"missing lexer ${lexerEntity.value}")
      schema <- sourceEntityAt(graph, lexerEntity, "schema")
      _ <- if Set(EntityId("lexer5.schema"), EntityId("lexer6.schema")).contains(schema) then Right(()) else Left(s"${lexerEntity.value} does not select lexer5.schema or lexer6.schema")
      rulePorts = lexerNode.ports.filter(p => p.direction == Direction.In && p.name.startsWith("rule")).sortBy(_.name)
      expected = (1 to rulePorts.size).map(i => f"rule$i%02d").toVector
      _ <- if rulePorts.nonEmpty && rulePorts.map(_.name) == expected then Right(()) else Left(s"${lexerEntity.value} has non-canonical lexer rule ports")
      ruleEntities <- sequence(rulePorts.map(port => sourceEntityAt(graph, lexerEntity, port.name)))
      rules <- sequence(ruleEntities.map(entity => decodeLexerRule(graph, spec.entity, entity)))
      _ <- if rules.map(_.priority).distinct.size == rules.size then Right(()) else Left(s"${lexerEntity.value} contains duplicate lexer rule priorities")
      _ <- if rules.map(_.name).distinct.size == rules.size then Right(()) else Left(s"${lexerEntity.value} contains duplicate lexer rule names")
    yield rules.sortBy(rule => (rule.priority, rule.entity.value))

  private def decodeLexerRule(graph: Graph, language: EntityId, entity: EntityId): Either[String, LexerRule] =
    graph.entity(entity) match
      case Some(node) if node.kind == "lexer.rule" && node.attrs.get("language").contains(language.value) =>
        for
          name <- required(node, "name", entity.value)
          priority <- node.attrs.get("priority").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer priority")
          _ <- if priority >= 0 then Right(()) else Left(s"${entity.value} has negative priority")
          bodyEntity <- sourceEntityAt(graph, entity, "body")
          body <- decodeLexerExpr(graph, language, bodyEntity, Set.empty)
          sortEntity <- sourceEntityAt(graph, entity, "sort")
          _ <- expectKind(graph, sortEntity, "language.sort")
          sortNode <- graph.entity(sortEntity).toRight(s"missing sort ${sortEntity.value}")
          _ <- if sortNode.attrs.get("language").contains(language.value) then Right(()) else Left(s"${sortEntity.value} is outside ${language.value}")
          sort <- required(sortNode, "name", sortEntity.value)
          constructor <- sourceEntityAt(graph, entity, "constructor")
          _ <- expectKind(graph, constructor, "language.constructor")
          ctorNode <- graph.entity(constructor).toRight(s"missing constructor ${constructor.value}")
          _ <- if ctorNode.attrs.get("language").contains(language.value) then Right(()) else Left(s"${constructor.value} is outside ${language.value}")
          constructorSort <- sourceEntityAt(graph, constructor, "sort")
          _ <- if constructorSort == sortEntity then Right(()) else Left(s"${constructor.value} sort edge does not match ${sortEntity.value}")
          ctor <- decodeConstructor(graph, constructor, ctorNode)
          _ <- if ctor.sort == sort && ctor.fields.isEmpty then Right(()) else Left(s"${constructor.value} is not a leaf constructor of lexical sort $sort")
          codec <-
            if node.ports.exists(_.name == "codec") then
              sourceEntityAt(graph, entity, "codec").flatMap(codecEntity => decodeLexerCodec(graph, language, codecEntity)).map(Some(_))
            else Right(None)
        yield LexerRule(entity, name, priority, sort, constructor, body, codec)
      case Some(node) => Left(s"${entity.value} is ${node.kind}, not a lexer.rule owned by ${language.value}")
      case None => Left(s"missing lexer rule ${entity.value}")

  private def decodeLexerCodec(
      graph: Graph,
      language: EntityId,
      entity: EntityId
  ): Either[String, LexerCodec] =
    graph.entity(entity) match
      case None => Left(s"missing lexer codec ${entity.value}")
      case Some(node) if node.attrs.get("language") != Some(language.value) =>
        Left(s"lexer codec ${entity.value} is outside ${language.value}")
      case Some(node) =>
        node.kind match
          case "lexer.codec.identity" =>
            Right(LexerCodec.Identity(entity))
          case "lexer.codec.decimal-natural" =>
            for
              radix <- node.attrs.get("radix").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer radix")
              _ <- if radix == 10 then Right(()) else Left(s"${entity.value} requests unsupported radix $radix")
              leading <- required(node, "leading-zeros", entity.value)
              _ <- if leading == "strip" then Right(()) else Left(s"${entity.value} requests unsupported leading-zero policy $leading")
              zero <- required(node, "zero", entity.value)
              _ <- if zero == "0" then Right(()) else Left(s"${entity.value} requests unsupported zero spelling $zero")
            yield LexerCodec.DecimalNatural(entity, radix, true, zero)
          case "lexer.codec.quoted-string" =>
            for
              quote <- required(node, "quote", entity.value)
              escape <- required(node, "escape", entity.value)
              _ <- if quote.codePointCount(0, quote.length) == 1 && escape.codePointCount(0, escape.length) == 1 then Right(()) else Left(s"${entity.value} quote and escape must be one code point")
              children <- orderedLexerChildren(graph, entity, node, "escape")
              mappings <- sequence(children.map(child => decodeEscape(graph, language, child)))
              _ <- if mappings.map(_.source).distinct.size == mappings.size then Right(()) else Left(s"${entity.value} contains duplicate escape source codes")
              _ <- if mappings.map(_.value).distinct.size == mappings.size then Right(()) else Left(s"${entity.value} contains non-canonical duplicate escape values")
              _ <- if mappings.exists(_.value == quote) && mappings.exists(_.value == escape) then Right(()) else Left(s"${entity.value} must encode quote and escape")
            yield LexerCodec.QuotedString(entity, quote, escape, mappings)
          case other => Left(s"unsupported lexer codec kind $other at ${entity.value}")

  private def decodeEscape(graph: Graph, language: EntityId, entity: EntityId): Either[String, EscapeSpec] =
    graph.entity(entity) match
      case Some(node) if node.kind == "lexer.escape" && node.attrs.get("language").contains(language.value) =>
        for
          source <- required(node, "source", entity.value)
          codePoint <- node.attrs.get("value-codepoint").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer value-codepoint")
          _ <- if source.codePointCount(0, source.length) == 1 then Right(()) else Left(s"${entity.value} escape source must be one code point")
          _ <- if Character.isValidCodePoint(codePoint) && (codePoint < 0xD800 || codePoint > 0xDFFF) then Right(()) else Left(s"${entity.value} has invalid Unicode value-codepoint")
          value = new String(Character.toChars(codePoint))
        yield EscapeSpec(entity, source, value)
      case Some(node) => Left(s"${entity.value} is ${node.kind}, not a lexer.escape owned by ${language.value}")
      case None => Left(s"missing lexer escape ${entity.value}")

  private def decodeLexerExpr(
      graph: Graph,
      language: EntityId,
      entity: EntityId,
      visiting: Set[EntityId]
  ): Either[String, LexerExpr] =
    if visiting.contains(entity) then Left(s"cyclic lexer expression at ${entity.value}")
    else
      graph.entity(entity) match
        case None => Left(s"missing lexer expression ${entity.value}")
        case Some(node) if node.attrs.get("language") != Some(language.value) => Left(s"lexer expression ${entity.value} is outside ${language.value}")
        case Some(node) =>
          val next = visiting + entity
          node.kind match
            case "lexer.literal" =>
              required(node, "value", entity.value).flatMap(value => if value.nonEmpty then Right(LexerExpr.Literal(value)) else Left(s"empty lexer literal ${entity.value}"))
            case "lexer.char-class" =>
              required(node, "primitive", entity.value).flatMap { primitive =>
                if Set(
                    "unicode-identifier-start-or-underscore",
                    "unicode-identifier-part-or-underscore",
                    "unicode-digit",
                    "string-unescaped",
                    "string-escape-code"
                  ).contains(primitive) then Right(LexerExpr.CharClass(primitive))
                else Left(s"${entity.value} requests unsupported character class $primitive")
              }
            case "lexer.sequence" =>
              orderedLexerChildren(graph, entity, node, "item").flatMap(children => sequence(children.map(child => decodeLexerExpr(graph, language, child, next))).map(LexerExpr.Sequence.apply))
            case "lexer.choice" =>
              orderedLexerChildren(graph, entity, node, "alt").flatMap(children => sequence(children.map(child => decodeLexerExpr(graph, language, child, next))).map(LexerExpr.Choice.apply))
            case "lexer.repeat" =>
              for
                bodyEntity <- sourceEntityAt(graph, entity, "body")
                body <- decodeLexerExpr(graph, language, bodyEntity, next)
                min <- node.attrs.get("min").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer min")
                max <- node.attrs.get("max").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max")
                _ <- if min >= 0 && max >= min && max <= 4096 then Right(()) else Left(s"${entity.value} has invalid lexical repetition bounds")
              yield LexerExpr.Repeat(body, min, max)
            case other => Left(s"unsupported lexer expression kind $other at ${entity.value}")

  private def orderedLexerChildren(graph: Graph, entity: EntityId, node: Node, prefix: String): Either[String, Vector[EntityId]] =
    val ports = node.ports.filter(p => p.direction == Direction.In && p.name.startsWith(prefix)).sortBy(_.name)
    val expected = (1 to ports.size).map(i => f"$prefix$i%02d").toVector
    if ports.isEmpty then Left(s"empty lexer $prefix collection ${entity.value}")
    else if ports.map(_.name) != expected then Left(s"non-canonical lexer $prefix ports on ${entity.value}")
    else sequence(ports.map(port => sourceEntityAt(graph, entity, port.name)))

  private def grammarLiterals(expr: GrammarExpr): Vector[String] = expr match
    case GrammarExpr.Literal(value) => Vector(value)
    case GrammarExpr.Reference(_, _) => Vector.empty
    case GrammarExpr.Sequence(items) => items.flatMap(grammarLiterals)
    case GrammarExpr.Choice(alternatives, _) => alternatives.flatMap(grammarLiterals)
    case GrammarExpr.Optional(body, _) => grammarLiterals(body)
    case GrammarExpr.Repeat(body, _, _, _) => grammarLiterals(body)
    case GrammarExpr.Capture(_, _, _, body, _, _) => grammarLiterals(body)
    case GrammarExpr.Token(_, _, _, _) => Vector.empty
    case GrammarExpr.LayoutToken(_) => Vector.empty
    case GrammarExpr.Suite(_, _, _, _, _, _, _) => Vector.empty
    case GrammarExpr.Hole(surface, _) => Vector(surface)

  private def scanGraphLexer(text: String, literals: Vector[String], lexer: LexerSpec): Either[String, Vector[LexToken]] =
    val ordered = literals.distinct.sortBy(value => (-value.length, value))
    val out = Vector.newBuilder[LexToken]
    var at = 0
    while at < text.length do
      if Character.isWhitespace(text.charAt(at)) then at += 1
      else lexer.lineComment match
        case Some(prefix) if text.startsWith(prefix, at) =>
          val newline = text.indexOf('\n', at + prefix.length)
          at = if newline < 0 then text.length else newline + 1
        case _ =>
          val matches = ordered.filter(literal => text.startsWith(literal, at) && lexicalBoundary(text, at, literal))
          matches.headOption match
            case Some(token) =>
              out += LexToken(token, None)
              at += token.length
            case None => return Left(lexicalFailure(lexer, text, at))
    Right(out.result())

  private def scanLexicalGraphDocument(
      text: String,
      literals: Vector[String],
      rules: Vector[LexerRule],
      lexer: LexerSpec
  ): Either[String, SourceDocument] =
    final case class Candidate(end: Int, priority: Int, key: String, token: LexToken, codec: Option[LexerCodec])
    val orderedLiterals = literals.distinct.sorted
    val out = Vector.newBuilder[SourceToken]
    var pending = Vector.empty[Trivia]
    var at = 0

    def span(start: Int, end: Int): SourceSpan = SourceSpan(utf8Offset(text, start), utf8Offset(text, end))

    while at < text.length do
      if Character.isWhitespace(text.charAt(at)) then
        val start = at
        at += 1
        while at < text.length && Character.isWhitespace(text.charAt(at)) do at += 1
        pending :+= Trivia("whitespace", text.substring(start, at), span(start, at))
      else lexer.lineComment match
        case Some(prefix) if text.startsWith(prefix, at) =>
          val start = at
          val newline = text.indexOf('\n', at + prefix.length)
          at = if newline < 0 then text.length else newline
          pending :+= Trivia("line-comment", text.substring(start, at), span(start, at))
        case _ =>
          val literalCandidates = orderedLiterals.flatMap { literal =>
            if text.startsWith(literal, at) && lexicalBoundary(text, at, literal) then
              Vector(Candidate(at + literal.length, 0, s"literal:$literal", LexToken(literal, None), None))
            else Vector.empty
          }
          val ruleCandidates = rules.flatMap { rule =>
            matchLexerExpr(rule.body, text, at, 0).filter(_ > at).distinct.map { end =>
              Candidate(end, rule.priority, s"rule:${rule.entity.value}", LexToken(text.substring(at, end), Some(rule.entity)), rule.codec)
            }
          }
          val candidates = literalCandidates ++ ruleCandidates
          candidates.sortBy(candidate => (-(candidate.end - at), candidate.priority, candidate.key)).headOption match
            case Some(chosen) =>
              val decoded = chosen.codec match
                case None => Right(chosen.token)
                case Some(codec) => decodeLexicalCodec(codec, chosen.token.text).map(value => chosen.token.copy(value = Some(value)))
              decoded match
                case Left(error) => return Left(error)
                case Right(token) =>
                  out += SourceToken(token, span(at, chosen.end), pending)
                  pending = Vector.empty
                  at = chosen.end
            case None => return Left(lexicalFailure(lexer, text, at))
    Right(SourceDocument(text, out.result(), pending))

  private def buildAstSourceMap(
      graph: Graph,
      language: EntityId,
      tree: Tree,
      document: SourceDocument
  ): Either[String, AstSourceMap] =
    for
      prods <- productions(graph, language)
      _ <- if document.tokens.nonEmpty then Right(()) else Left("cannot source-map an empty token document")
      entries <- mapTreeSource(prods, tree, document, 0, document.tokens.size, "$")
    yield AstSourceMap(entries)

  private def mapTreeSource(
      productions: Vector[Production],
      tree: Tree,
      document: SourceDocument,
      lo: Int,
      hi: Int,
      path: String
  ): Either[String, Vector[AstSourceEntry]] =
    if lo >= hi || lo < 0 || hi > document.tokens.size then Left(s"invalid source token envelope for $path")
    else
      val span = SourceSpan(document.tokens(lo).span.startByte, document.tokens(hi - 1).span.endByte)
      val root = AstSourceEntry(path, tree.constructor, span)
      val children = tree.fieldValues.flatMap { case (name, value) => value match
        case FieldValue.One(child) => Vector((name, child))
        case FieldValue.Optional(Some(child)) => Vector((name, child))
        case FieldValue.Many(values) => values.zipWithIndex.map { case (child, index) => s"$name[$index]" -> child }
        case _ => Vector.empty
      }
      def mapChildren(
          remaining: Vector[(String, Tree)],
          cursor: Int,
          acc: Vector[AstSourceEntry]
      ): Either[String, Vector[AstSourceEntry]] =
        remaining.headOption match
          case None => Right(acc)
          case Some((segment, child)) =>
            findTreeSlice(productions, document.tokens.map(_.token), child, cursor, hi) match
              case None => Left(s"cannot locate source envelope for $path.$segment")
              case Some((start, end)) =>
                mapTreeSource(productions, child, document, start, end, s"$path.$segment").flatMap { entries =>
                  mapChildren(remaining.tail, end, acc ++ entries)
                }
      mapChildren(children, lo, Vector(root))

  private def findTreeSlice(
      productions: Vector[Production],
      tokens: Vector[LexToken],
      expected: Tree,
      from: Int,
      until: Int
  ): Option[(Int, Int)] =
    var start = from
    while start < until do
      var end = start + 1
      while end <= until do
        val slice = tokens.slice(start, end)
        val matches = parseSortPrecedence(productions, slice, expected.sort, 0, 0, 0)
          .exists { case (tree, next) => next == slice.size && tree == expected }
        if matches then return Some(start -> end)
        end += 1
      start += 1
    None

  private def deriveLayoutEvents(
      text: String,
      policy: LayoutPolicy,
      lineComment: Option[String]
  ): Either[String, Vector[LayoutEvent]] =
    val events = Vector.newBuilder[LayoutEvent]
    val stack = scala.collection.mutable.ArrayBuffer(policy.rootIndent)
    var lineStart = 0
    var seenCode = false
    var previousLineEnd = 0

    while lineStart <= text.length do
      val newlineAt = text.indexOf('\n', lineStart)
      val lineEnd = if newlineAt < 0 then text.length else newlineAt
      val line = text.substring(lineStart, lineEnd)
      var spaces = 0
      while spaces < line.length && line.charAt(spaces) == ' ' do spaces += 1
      if spaces < line.length && line.charAt(spaces) == '\t' && policy.tabs == "reject" then
        return Left(s"tab indentation is forbidden at byte ${utf8Offset(text, lineStart + spaces)}")
      val rest = line.substring(spaces)
      val blank = rest.trim.isEmpty
      val commentOnly = lineComment.exists(prefix => rest.startsWith(prefix))
      if !blank && !commentOnly then
        if spaces % policy.indentUnit != 0 then return Left(s"indentation $spaces is not a multiple of ${policy.indentUnit}")
        if !seenCode && spaces != policy.rootIndent then return Left(s"first code line must start at indentation ${policy.rootIndent}")
        if seenCode then events += LayoutEvent(policy.newlineToken, utf8Offset(text, previousLineEnd), stack.size - 1)
        if spaces > stack.last then
          stack += spaces
          events += LayoutEvent(policy.indentToken, utf8Offset(text, lineStart + spaces), stack.size - 1)
        else if spaces < stack.last then
          while stack.size > 1 && spaces < stack.last do
            stack.remove(stack.size - 1)
            events += LayoutEvent(policy.dedentToken, utf8Offset(text, lineStart + spaces), stack.size - 1)
          if spaces != stack.last then return Left(s"indentation $spaces does not match an open layout level")
        seenCode = true
        previousLineEnd = lineEnd
      if newlineAt < 0 then lineStart = text.length + 1 else lineStart = newlineAt + 1

    if seenCode then events += LayoutEvent(policy.newlineToken, utf8Offset(text, previousLineEnd), stack.size - 1)
    while stack.size > 1 do
      stack.remove(stack.size - 1)
      events += LayoutEvent(policy.dedentToken, text.getBytes(StandardCharsets.UTF_8).length, stack.size - 1)
    Right(events.result())

  private def interleaveLayoutTokens(
      document: SourceDocument,
      events: Vector[LayoutEvent],
      policy: LayoutPolicy
  ): Vector[LexToken] =
    val effective =
      if events.lastOption.exists(event => event.token == policy.newlineToken && event.depth == 0) then events.dropRight(1)
      else events
    val out = Vector.newBuilder[LexToken]
    var eventAt = 0
    document.tokens.foreach { sourceToken =>
      while eventAt < effective.size && effective(eventAt).atByte <= sourceToken.span.startByte do
        out += LexToken("", None, None, Some(effective(eventAt).token))
        eventAt += 1
      out += sourceToken.token
    }
    while eventAt < effective.size do
      out += LexToken("", None, None, Some(effective(eventAt).token))
      eventAt += 1
    out.result()

  private def renderPrintPieces(
      graph: Graph,
      spec: LanguageSpec,
      pieces: Vector[PrintPiece]
  ): Either[String, String] =
    if pieces.forall { case PrintPiece.Text(_) => true; case _ => false } then
      Right(pieces.collect { case PrintPiece.Text(value) => value }.mkString(" "))
    else
      layoutPolicyDefinition(graph, spec).flatMap { policy =>
        val out = new StringBuilder
        var indentation = policy.rootIndent
        var lineStart = true
        var error: Option[String] = None
        var at = 0
        while at < pieces.size && error.isEmpty do
          pieces(at) match
            case PrintPiece.Text(value) =>
              if lineStart then
                out.append(" " * indentation)
                lineStart = false
              else out.append(' ')
              out.append(value)
            case PrintPiece.Layout(token) if token == policy.newlineToken =>
              out.append('\n')
              lineStart = true
            case PrintPiece.Layout(token) if token == policy.indentToken =>
              if !lineStart then error = Some("INDENT must occur at a line boundary")
              else indentation += policy.indentUnit
            case PrintPiece.Layout(token) if token == policy.dedentToken =>
              if !lineStart then error = Some("DEDENT must occur at a line boundary")
              else
                indentation -= policy.indentUnit
                if indentation < policy.rootIndent then error = Some("layout printer dedented below root")
            case PrintPiece.Layout(token) => error = Some(s"unknown layout token ${token.value}")
          at += 1
        error match
          case Some(message) => Left(message)
          case None if indentation != policy.rootIndent => Left("layout printer finished with an open indentation level")
          case None => Right(out.result())
      }

  private def utf8Offset(text: String, utf16Offset: Int): Int =
    text.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length

  private def scanLexicalGraph(
      text: String,
      literals: Vector[String],
      rules: Vector[LexerRule],
      lexer: LexerSpec
  ): Either[String, Vector[LexToken]] =
    final case class Candidate(end: Int, priority: Int, key: String, token: LexToken, codec: Option[LexerCodec])
    val orderedLiterals = literals.distinct.sorted
    val out = Vector.newBuilder[LexToken]
    var at = 0
    while at < text.length do
      if Character.isWhitespace(text.charAt(at)) then at += 1
      else lexer.lineComment match
        case Some(prefix) if text.startsWith(prefix, at) =>
          val newline = text.indexOf('\n', at + prefix.length)
          at = if newline < 0 then text.length else newline + 1
        case _ =>
          val literalCandidates = orderedLiterals.flatMap { literal =>
            if text.startsWith(literal, at) && lexicalBoundary(text, at, literal) then
              Vector(Candidate(at + literal.length, 0, s"literal:$literal", LexToken(literal, None), None))
            else Vector.empty
          }
          val ruleCandidates = rules.flatMap { rule =>
            matchLexerExpr(rule.body, text, at, 0).filter(_ > at).distinct.map { end =>
              Candidate(end, rule.priority, s"rule:${rule.entity.value}", LexToken(text.substring(at, end), Some(rule.entity)), rule.codec)
            }
          }
          val candidates = literalCandidates ++ ruleCandidates
          candidates.sortBy(candidate => (-(candidate.end - at), candidate.priority, candidate.key)).headOption match
            case Some(chosen) =>
              chosen.codec match
                case None => out += chosen.token
                case Some(codec) =>
                  decodeLexicalCodec(codec, chosen.token.text) match
                    case Left(error) => return Left(error)
                    case Right(value) => out += chosen.token.copy(value = Some(value))
              at = chosen.end
            case None => return Left(lexicalFailure(lexer, text, at))
    Right(out.result())

  private def decodeLexicalCodec(codec: LexerCodec, source: String): Either[String, String] = codec match
    case LexerCodec.Identity(_) =>
      if source.nonEmpty then Right(source) else Left("identity lexical codec cannot decode an empty token")
    case LexerCodec.DecimalNatural(entity, radix, stripLeadingZeros, zero) =>
      val digits = Vector.newBuilder[Char]
      var at = 0
      while at < source.length do
        val codePoint = source.codePointAt(at)
        val digit = Character.digit(codePoint, radix)
        if digit < 0 then return Left(s"${entity.value} cannot decode non-digit lexical input")
        digits += ('0' + digit).toChar
        at += Character.charCount(codePoint)
      val raw = digits.result().mkString
      if raw.isEmpty then Left(s"${entity.value} cannot decode an empty natural")
      else if stripLeadingZeros then
        val normalized = raw.dropWhile(_ == '0')
        Right(if normalized.isEmpty then zero else normalized)
      else Right(raw)
    case LexerCodec.QuotedString(entity, quote, escape, mappings) =>
      if !source.startsWith(quote) || !source.endsWith(quote) || source.length < quote.length * 2 then
        Left(s"${entity.value} cannot decode an unquoted string")
      else
        val body = source.substring(quote.length, source.length - quote.length)
        val bySource = mappings.map(m => m.source -> m.value).toMap
        val out = new StringBuilder
        var at = 0
        while at < body.length do
          if body.startsWith(escape, at) then
            val codeAt = at + escape.length
            if codeAt >= body.length then return Left(s"${entity.value} contains a dangling escape")
            val codePoint = body.codePointAt(codeAt)
            val code = new String(Character.toChars(codePoint))
            bySource.get(code) match
              case Some(value) => out.append(value)
              case None => return Left(s"${entity.value} does not define escape $code")
            at = codeAt + Character.charCount(codePoint)
          else
            val codePoint = body.codePointAt(at)
            val value = new String(Character.toChars(codePoint))
            if value == quote then return Left(s"${entity.value} contains an unescaped quote")
            out.append(value)
            at += Character.charCount(codePoint)
        Right(out.result())

  private def encodeLexicalCodec(codec: LexerCodec, value: String): Either[String, String] = codec match
    case LexerCodec.Identity(_) =>
      if value.nonEmpty then Right(value) else Left("identity lexical codec cannot encode an empty token")
    case LexerCodec.DecimalNatural(entity, radix, stripLeadingZeros, zero) =>
      if radix != 10 || !stripLeadingZeros then Left(s"${entity.value} requests unsupported decimal encoding policy")
      else if value == zero then Right(zero)
      else if value.nonEmpty && value.forall(c => c >= '0' && c <= '9') && value.head != '0' then Right(value)
      else Left(s"${entity.value} requires canonical non-negative decimal semantic values")
    case LexerCodec.QuotedString(entity, quote, escape, mappings) =>
      val byValue = mappings.map(m => m.value -> m.source).toMap
      val out = new StringBuilder
      out.append(quote)
      var at = 0
      while at < value.length do
        val codePoint = value.codePointAt(at)
        val ch = new String(Character.toChars(codePoint))
        byValue.get(ch) match
          case Some(source) => out.append(escape).append(source)
          case None =>
            if ch == quote || ch == escape || ch == "\n" || ch == "\r" || ch == "\t" then
              return Left(s"${entity.value} has no canonical escape for a required character")
            out.append(ch)
        at += Character.charCount(codePoint)
      out.append(quote)
      Right(out.result())

  private def matchLexerExpr(expr: LexerExpr, text: String, at: Int, depth: Int): Vector[Int] =
    if at > text.length || depth > text.length + 32 then Vector.empty
    else expr match
      case LexerExpr.Literal(value) => if text.startsWith(value, at) then Vector(at + value.length) else Vector.empty
      case LexerExpr.CharClass(primitive) =>
        if at >= text.length then Vector.empty
        else
          val codePoint = text.codePointAt(at)
          if lexicalPrimitiveMatches(primitive, codePoint) then Vector(at + Character.charCount(codePoint)) else Vector.empty
      case LexerExpr.Sequence(items) =>
        items.foldLeft(Vector(at)) { (positions, item) =>
          positions.flatMap(position => matchLexerExpr(item, text, position, depth + 1)).distinct
        }
      case LexerExpr.Choice(alternatives) => alternatives.flatMap(alt => matchLexerExpr(alt, text, at, depth + 1)).distinct
      case LexerExpr.Repeat(body, min, max) =>
        def loop(count: Int, position: Int): Vector[Int] =
          val stop = if count >= min then Vector(position) else Vector.empty
          if count >= max then stop
          else
            val next = matchLexerExpr(body, text, position, depth + count + 1).filter(_ > position).distinct
            stop ++ next.flatMap(after => loop(count + 1, after))
        loop(0, at).distinct

  private def lexicalPrimitiveMatches(primitive: String, codePoint: Int): Boolean = primitive match
    case "unicode-identifier-start-or-underscore" => Character.isUnicodeIdentifierStart(codePoint) || codePoint == '_'.toInt
    case "unicode-identifier-part-or-underscore" => Character.isUnicodeIdentifierPart(codePoint) || codePoint == '_'.toInt
    case "unicode-digit" => Character.isDigit(codePoint)
    case "string-unescaped" => codePoint != '"'.toInt && codePoint != '\\'.toInt && codePoint != '\n'.toInt && codePoint != '\r'.toInt
    case "string-escape-code" => Set('"'.toInt, '\\'.toInt, 'n'.toInt, 't'.toInt, 'r'.toInt).contains(codePoint)
    case _ => false

  private def lexicalFailure(lexer: LexerSpec, text: String, at: Int): String =
    s"${lexer.entity.value} cannot tokenize input at UTF-16 offset $at near '${text.slice(at, math.min(text.length, at + 12))}'"

  private def lexicalBoundary(text: String, at: Int, literal: String): Boolean =
    def wordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_'
    if literal.nonEmpty && wordChar(literal.last) then
      val end = at + literal.length
      end >= text.length || !wordChar(text.charAt(end))
    else true

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, value) =>
      for
        xs <- acc
        x <- value
      yield xs :+ x
    }
