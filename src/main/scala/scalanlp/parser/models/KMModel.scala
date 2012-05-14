package scalanlp.parser
package models

import scalala.tensor.dense.DenseVector
import projections.GrammarRefinements
import scalanlp.epic.Feature
import epic._
import scalanlp.trees.{AnnotatedLabel, BinarizedTree}
import java.io.File
import scalala.library.Library
import features._
import scalala.tensor.Counter

class KMModel[L, L2, W](featurizer: Featurizer[L2, W],
                        ann: (BinarizedTree[L], Seq[W]) => BinarizedTree[L2],
                        val projections: GrammarRefinements[L, L2],
                        baseFactory: DerivationScorer.Factory[L, W],
                        grammar: Grammar[L],
                        lexicon: Lexicon[L, W],
                        initialFeatureVal: (Feature => Option[Double]) = {
                          _ => None
                        }) extends ParserModel[L, W] {
  type Inference = DiscParserInference[L, W]

  val indexedFeatures: FeatureIndexer[L, L2, W] = FeatureIndexer(grammar, lexicon, featurizer, projections)

  def featureIndex = indexedFeatures.index

  override def initialValueForFeature(f: Feature) = initialFeatureVal(f) getOrElse 0.0

  def emptyCounts = new ExpectedCounts(featureIndex)

  def inferenceFromWeights(weights: DenseVector[Double]) = {
    val lexicon = new FeaturizedLexicon(weights, indexedFeatures)
    val grammar = FeaturizedGrammar(this.grammar, this.lexicon, projections, weights, indexedFeatures, lexicon)
    def reannotate(tree: BinarizedTree[L], words: Seq[W]) = {
      val annotated = ann(tree, words)

      val localized = annotated.map {
        l =>
          projections.labels.project(l) -> projections.labels.localize(l)
      }

      localized
    }

    new DiscParserInference(indexedFeatures, reannotate, grammar, baseFactory)
  }

  def extractParser(weights: DenseVector[Double]): ChartParser[L, W] = {
    val inf = inferenceFromWeights(weights)
    SimpleChartParser(inf.grammar)
  }

  def expectedCountsToObjective(ecounts: ExpectedCounts) = {
    (ecounts.loss, ecounts.counts)
  }

}

case class KMModelFactory(baseParser: ParserParams.BaseParser,
                          constraints: ParserParams.Constraints[AnnotatedLabel, String],
                          pipeline: KMPipeline,
                          oldWeights: File = null) extends ParserModelFactory[AnnotatedLabel, String] {
  type MyModel = KMModel[AnnotatedLabel, AnnotatedLabel, String]

  def make(trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]]): MyModel = {
    val transformed = trainTrees.par.map {
      ti =>
        val t = pipeline(ti.tree, ti.words)
        TreeInstance(ti.id, t, ti.words)
    }.seq.toIndexedSeq

    val (initLexicon, initBinaries, initUnaries) = this.extractBasicCounts(transformed)


    val (grammar, lexicon) = baseParser.xbarGrammar(trainTrees)
    val refGrammar = Grammar(AnnotatedLabel.TOP, initBinaries, initUnaries)
    val indexedRefinements = GrammarRefinements(grammar, refGrammar, {
      (_: AnnotatedLabel).baseAnnotatedLabel
    })

    val (xbarWords, xbarBinaries, xbarUnaries) = this.extractBasicCounts(trainTrees.map(_.mapLabels(_.baseAnnotatedLabel)))
    val baseFactory = DerivationScorerFactory.generative(grammar, lexicon, xbarBinaries, xbarUnaries, xbarWords)
    val cFactory = constraints.cachedFactory(baseFactory)

    val gen = new WordShapeFeaturizer(Library.sum(initLexicon))
    def labelFlattener(l: AnnotatedLabel) = {
      val basic = Seq(l, l.copy(features = Set.empty))
      basic map {
        IndicatorFeature(_)
      }
    }
    val feat = new GenFeaturizer[AnnotatedLabel, String](gen, labelFlattener _)

    val featureCounter = if (oldWeights ne null) {
      scalanlp.util.readObject[Counter[Feature, Double]](oldWeights)
    } else {
      Counter[Feature, Double]()
    }
    new KMModel[AnnotatedLabel, AnnotatedLabel, String](feat, pipeline, indexedRefinements, cFactory, grammar, lexicon, {
      featureCounter.get(_)
    })
  }

}