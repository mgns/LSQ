package org.aksw.simba.lsq.util;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.aksw.jena_sparql_api.concepts.Concept;
import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.core.utils.ServiceUtils;
import org.aksw.jena_sparql_api.utils.ElementUtils;
import org.aksw.jena_sparql_api.utils.TripleUtils;
import org.aksw.jena_sparql_api.utils.Vars;
import org.aksw.simba.lsq.vocab.LSQ;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.aggregate.AggCount;
import org.apache.jena.util.ResourceUtils;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.spin.vocabulary.SP;

/**
 * SPIN utils - mainly for extracting Jena Triple and BasicPattern objects from SPIN RDF.
 * TODO Maybe the spin library already comes with a proper spin reader?
 *
 *
 */
public class SpinUtils {

    public static final Concept triplePatterns = Concept.create("PREFIX sp: <http://spinrdf.org/sp#>", "x", "?x sp:subject ?s ; sp:predicate ?p ; sp:object ?o");
    public static final Concept basicPatterns = Concept.create("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> PREFIX sp: <http://spinrdf.org/sp#>", "x", "?foo !rdf:rest ?x . ?x (rdf:rest)*/rdf:first [ sp:subject ?s ; sp:predicate ?p ; sp:object ?o ]");

    public static final Concept subjects = Concept.create("PREFIX sp: <http://spinrdf.org/sp#>", "y", "?x sp:subject ?y");
    public static final Concept predicates = Concept.create("PREFIX sp: <http://spinrdf.org/sp#>", "y", "?x sp:predicate ?y");
    public static final Concept objects = Concept.create("PREFIX sp: <http://spinrdf.org/sp#>", "y", "?x sp:object ?y");

    public static int fetchTriplePatternExtensionSize(QueryExecutionFactory qef, Triple triple) {

        Var c = Var.alloc("_c_");
        // TODO Move to QueryGenerationUtils
        Var d = Var.alloc("_d_");

        Query query = new Query();
        query.getProject().add(c, new ExprAggregator(d, new AggCount()));
        query.setQuerySelectType();
        query.setQueryPattern(ElementUtils.createElement(triple));

        int result = ServiceUtils.fetchInteger(qef, query, c);
        return result;
    }

    public static Map<Resource, BasicPattern> indexBasicPatterns(Resource r) {
        Model spinModel = ResourceUtils.reachableClosure(r);
        Map<Resource, BasicPattern> result = indexBasicPatterns(spinModel);
        return result;
    }

    public static Map<Resource, BasicPattern> indexBasicPatterns(Model spinModel) {
//        spinModel.write(System.out, "NTRIPLES");

        List<Resource> ress = ConceptModelUtils.listResources(spinModel, basicPatterns);

//        ress.stream().forEach(x -> System.out.println("GOT RES: " + x));

        Map<Resource, BasicPattern> result = ress
                .stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        t -> {
                            Map<Resource, Triple> tmp = indexTriplePatterns(t);
                            BasicPattern r = new BasicPattern();
                            tmp.values().forEach(r::add);
                            return r;
                        }));

//        result.entrySet().forEach(x -> System.out.println("GOT: " + x));
        return result;
    }

    public static Map<Resource, Triple> indexTriplePatterns(Resource res) {
        Model spinModel = ResourceUtils.reachableClosure(res);
        Map<Resource, Triple> result = indexTriplePatterns(spinModel);
        return result;
    }

    public static Map<Resource, Triple> indexTriplePatterns(Model spinModel) {
        Map<Resource, Triple> result = ConceptModelUtils.listResources(spinModel, triplePatterns)
                .stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        t -> readTriple(t).get()));
        return result;
    }

    public static void enrichWithHasTriplePattern(Resource targetRes, Resource spinRes) {
        Model spinModel = ResourceUtils.reachableClosure(spinRes);
        Map<Resource, Triple> triplePatternIndex = indexTriplePatterns(spinModel);

        triplePatternIndex.keySet().forEach(r ->
            targetRes.addProperty(LSQ.hasTriplePattern, r)
        );
    }


    public static void enrichWithTriplePatternText(Resource queryRes) {
        Model spinModel = ResourceUtils.reachableClosure(queryRes);
        Map<Resource, Triple> triplePatternIndex = indexTriplePatterns(spinModel);

        triplePatternIndex.forEach((r, t) -> r.inModel(queryRes.getModel())
                .addProperty(RDFS.label, TripleUtils.toNTripleString(t))
                );
                //.addProperty(LSQ.triplePatternText, TripleUtils.toNTripleString(t)));
    }


    public static void enrichModelWithTriplePatternExtensionSizes(Resource queryRes, Resource queryExecRes, QueryExecutionFactory dataQef) {
        Model spinModel = ResourceUtils.reachableClosure(queryRes);
        Map<Resource, Triple> triplePatternIndex = indexTriplePatterns(spinModel);

        triplePatternIndex.forEach((r, t) -> {
            int tripleCount = fetchTriplePatternExtensionSize(dataQef, t);
            //double selectivity = tripleCount / (double)totalTripleCount;

            spinModel.add(r, LSQ.triplePatternResultSize, spinModel.createTypedLiteral(tripleCount));
        });
    }

    public static long countTriplePattern(QueryExecutionFactory qef, Triple t) {
        Query query = new Query();
        query.setQuerySelectType();
        query.getProject().add(Vars.c, new ExprAggregator(Vars.x, new AggCount()));
        query.setQueryPattern(ElementUtils.createElement(t));

        QueryExecution qe = qef.createQueryExecution(query);
        long result = ServiceUtils.fetchInteger(qe, Vars.c);
        return result;
    }

    /**
     * :qe-123
     *     tripleSelec
     *
     * tpqe-123-p1
     *   forExec qe-123
     *   ofPattern q-123-p1
     *
     * @param queryRes
     * @param queryExecRes
     * @param qef
     * @param totalTripleCount
     */
    public static void enrichModelWithTriplePatternSelectivities(Resource queryRes, Resource queryExecRes, QueryExecutionFactory qef, long totalTripleCount) {

        Model spinModel = ResourceUtils.reachableClosure(queryRes);
        Map<Resource, Triple> triplePatternIndex = indexTriplePatterns(spinModel);

        int i = 0;
        //triplePatternIndex.entrySet().forEach(e -> {
        for(Entry<Resource, Triple> e : triplePatternIndex.entrySet()) {
            ++i;
            Resource r = e.getKey();
            Triple t = e.getValue();
            long count = countTriplePattern(qef, t);

            Resource queryTpExecRes = queryRes.getModel().createResource(queryExecRes.getURI() + "-tp-" + i);

            queryExecRes
                .addProperty(LSQ.hasTriplePatternExecution, queryTpExecRes);


            double selectivity = totalTripleCount == 0 ? 0 : count / (double)totalTripleCount;

            queryTpExecRes
                .addProperty(LSQ.hasTriplePattern, r)
                .addLiteral(LSQ.triplePatternResultSize, count)
                .addLiteral(LSQ.triplePatternSelectivity, selectivity);
        }

//        triplePatternIndex.keySet().forEach(r ->
//            queryRes.addProperty(LSQ.hasTriplePattern, r)
//        );


        //double selectivity = tripleCount / (double)totalTripleCount;


    }

    /**
     * Reads a single object for a given subject - predicate pair and
     * maps the corresponding object through a transformation function.
     *
     * Convenience function. model.listObjectsOfProperty(s, p).toList().stream().map(
     *
     *
     *
     * @param model
     * @param s
     * @param p
     * @param fn
     * @return
     */
    public static Optional<RDFNode> readObject(Resource s, Property p) {
        Optional<RDFNode> result = s.listProperties(p).toList().stream()
                .map(stmt -> stmt.getObject())
                .findFirst();

        //T result = tmp.isPresent() ? tmp.get() : null;
        return result;
    }

    /**
     * If the node is a variable, returns the variable.
     * Otherwise returns the given node
     *
     * @param model
     * @param node
     * @return
     */
    public static Node readNode(RDFNode node) {
        Model model = node.getModel();

        Node result;

        Node tmp = null;
        if(node != null & node.isResource()) {
            Resource r = node.asResource();
            RDFNode o = model.listObjectsOfProperty(r, SP.varName).toList().stream().findFirst().orElse(null);
            if(o != null) {
                String varName = o.asLiteral().getString();
                tmp = Var.alloc(varName);
            }
        }

        result = tmp == null
                ? node.asNode()
                : tmp;

        return result;
    }

    public static Optional<Triple> readTriple(Resource r) {
        Node s = readObject(r, SP.subject).map(x -> readNode(x)).orElse(null);
        Node p = readObject(r, SP.predicate).map(x -> readNode(x)).orElse(null);
        Node o = readObject(r, SP.object).map(x -> readNode(x)).orElse(null);

        Optional<Triple> result = s == null || p == null || o == null
                ? Optional.empty()
                : Optional.of(new Triple(s, p, o));

        return result;
    }

//
//
//            stats = stats + "\nlsqr:le-"+LogRDFizer.acronym+"-q"+LogRDFizer.queryHash+ " lsqv:hasTriplePattern lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+ " . \n";
//            stats = stats+" lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+" lsqv:triplePatternText \""+spText +"\" ; ";
//            stats = stats+ " lsqv:triplePatternSelectivity "+tpSel +" . ";
//
//
//
//        }
//
//
//
//                    stats = stats + "\nlsqr:le-"+LogRDFizer.acronym+"-q"+LogRDFizer.queryHash+ " lsqv:hasTriplePattern lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+ " . \n";
//                    stats = stats+" lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+" lsqv:triplePatternText \""+spText +"\" ; ";
//                    stats = stats+ " lsqv:triplePatternSelectivity "+tpSel +" . ";
//
//                }
//                else
//                {
//                    tpSel = getTriplePatternSelectivity(stmt,tp,endpoint,graph,endpointSize);
//                    tpSelCache.put(tp, tpSel);
//                //  System.out.println(tp + "  " +tpSel );
//                    stats = stats + "\nlsqr:le-"+LogRDFizer.acronym+"-q"+LogRDFizer.queryHash+ " lsqv:hasTriplePattern lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+ " . \n";
//                    stats = stats+" lsqr:q"+LogRDFizer.queryHash+"-p"+ tpNo+" lsqv:triplePatternText \""+spText +"\" ; ";
//                    stats = stats+ " lsqv:triplePatternSelectivity "+tpSel +" . ";
//                }
//                meanQrySel  = meanQrySel+ tpSel;
//                tpNo++;
//                //meanTPSelectivities.add(tpSel);
                //System.out.println("Average (across all datasets) Triple pattern selectivity: "+ meanTripleSel);
//            }
//        }
//        if(totalTriplePatterns==0)
//            meanQrySel =0;
//        else
//            meanQrySel = meanQrySel/totalTriplePatterns;
//        con.close();
//        stats = stats + "\nlsqr:le-"+LogRDFizer.acronym+"-q"+LogRDFizer.queryHash+" lsqv:meanTriplePatternSelectivity "+meanQrySel+" ;  ";
//
//        return stats;
//    }
}
