/**
 * The MIT License
 * Copyright © 2017 DTL
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package nl.dtls.fairdatapoint.database.ontology;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleIRIMapper;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OntologySearcher {
	
	private boolean filterPunctuation = true;
	private List<String> stopWords = getStopWords();
	
	static private OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
	static private OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();
	
	static private Logger log = LoggerFactory.getLogger(OntologySearcher.class);
	
	@Autowired
	public OntologySearcher() {
		
		log.info("beginning to index all ontologies");
		
		indexAllOntologies();
	}
	
	// keeps track of how frequent a keyword occurs in the ontologies
	private Map<String, Integer> keywordCount = new HashMap<String, Integer>();
	
	private void incrementKeywordCount(String keyword) {
		
		if (keywordCount.containsKey(keyword))
			
			keywordCount.put(keyword, keywordCount.get(keyword) + 1);
		else
			keywordCount.put(keyword, 1);
	}
	
	// keeps track of keywords that occur together
	private Map<String, List<String>> associations = new HashMap<String, List<String>>();
	
	private List<String> getAssociations(String key) {
				
		if (associations.containsKey(key))
			return associations.get(key);
		else
			return new ArrayList<String>();
	}
	
	private void associate(String key, String value) {
		
		if (!associations.containsKey(key))
			associations.put(key, new ArrayList<String>());
		
		associations.get(key).add(value);
	}
	
	// The higher this value, the more up front
	public double getKeywordRankingScore(String keyword) {
		
		return 1.0 / keywordCount.get(keyword);
	}
	
	private static File getCacheDirectory() throws IOException {
		
		File directory = new File("./fdp-ontology-cache");
		
		if (!directory.isDirectory()) {
			directory.mkdir();
		}
		
		return directory;
	}
	
	private static OWLOntology fetchThesaurus() throws IOException, OWLOntologyCreationException {
		
		File cachePath = new File(getCacheDirectory(), "Thesaurus_23.05e.OWL.zip");
		
		if (!cachePath.isFile()) {

			log.info("downloading {}", cachePath.getName());
			
			URL ontologyURL = new URL("https://evs.nci.nih.gov/ftp1/NCI_Thesaurus/archive/23.05e_Release/Thesaurus_23.05e.OWL.zip");
			
			FileUtils.copyURLToFile(ontologyURL, cachePath);
		}

		log.info("parsing {}", cachePath.getName());
		
		InputStream input = new FileInputStream(cachePath.toString());
		
		ZipInputStream zipInput = new ZipInputStream(input);
		
		zipInput.getNextEntry();
		
		return ontologyManager.loadOntologyFromOntologyDocument(zipInput);
	}

	public void indexAllOntologies() {
		
		try {
			OWLOntology thesaurus = fetchThesaurus();
			log.info("finished loading the thesaurus ontology");
			
			indexOntology(thesaurus);
			log.info("finished indexing the thesaurus ontology");
			
		} catch (IOException e) {
			log.error("I/O exception on indexing thesaurus: {}", e);
			
		} catch (OWLOntologyCreationException e) {
			log.error("ontology exception on indexing thesaurus: {}", e);
		}
	}
	
	private void indexOntology(OWLOntology ontology) {
		
		for (OWLClass cls : ontology.getClassesInSignature()) {
			
			indexClassAnnotations(ontology, cls);
		}
	}
	
	private void indexClassAnnotations(OWLOntology ontology, OWLClass cls) {
		
		
		Collection<OWLAnnotation> annotations =  EntitySearcher.getAnnotations(cls, ontology)
												 .collect(Collectors.toCollection(LinkedHashSet::new));
		
		for (OWLAnnotation annotation : annotations) {
				
			Optional<OWLLiteral> optionalLiteral = annotation.getValue().asLiteral();
			
			if (optionalLiteral.isPresent()) {
				
				Optional<String> optionalText = getStringFromLiteral(optionalLiteral.get());
				
				if (optionalText.isPresent()) {
					
					for (String key : getKeywordsFromString(optionalText.get())) {
					
						incrementKeywordCount(key);

						if (annotation.getProperty().isLabel()) {
						
							linkKeyToAnnotations(key, annotations);
						}
					}
				}
			}
		}
	}
	
	private void linkKeyToAnnotations(String key, Collection<OWLAnnotation> annotations) {
		
		for (OWLAnnotation annotation : annotations) {
			
			Optional<OWLLiteral> optionalLiteral = annotation.getValue().asLiteral();
			
			if (optionalLiteral.isPresent()) {
				
				Optional<String> optionalText = getStringFromLiteral(optionalLiteral.get());
				
				if (optionalText.isPresent()) {
				
					for (String word : getKeywordsFromString(optionalText.get())) {
						
						associate(key, word);
					}
				}
			}
		}
	}

	static Pattern literalStringPattern = Pattern.compile("^\\\"(.*)\\\"\\^\\^xsd:string$", Pattern.CASE_INSENSITIVE);
	
	private static Optional<String> getStringFromLiteral(OWLLiteral literal) {
		
		Matcher matcher = literalStringPattern.matcher(literal.toString());
		
		if (matcher.find()) {
			// matched string pattern
			return Optional.of(matcher.group(1));
		}
		else {
			// this is not a string
			return Optional.empty();
		}
	}

	private List<String> getKeywordsFromString(String input) {
		
		List<String> result = new ArrayList<String>();
		
		for (String word : input.toLowerCase().split(" ")) {
			
			if (filterPunctuation)
				word = wordWithoutPunctuation(word);
			
			if (!stopWords.contains(word) && word.length() > 3)
				result.add(word);
		}
		
		return result;
	}

	private static String wordWithoutPunctuation(String s) {
		
		String r = "";
		for (Character c : s.toCharArray()) 
		{
			if(Character.isLetterOrDigit(c)) {
				r += c;
			}
		}
		
		return r;
	}

	private List<String> getStopWords() {
		
		URL resourceURL = OntologySearcher.class.getResource("/english-stopwords.txt");
		if (resourceURL == null) {
			throw new NullPointerException("Got a null pointer while accessing resource: english-stopwords.txt");
		}
		
		Path path = Path.of(resourceURL.getPath());
		
		try {
			return Files.readAllLines(path);
			
		} catch (IOException e) {
			throw new RuntimeException("Cannot read from english-stopwords.txt: " + e);
		}
	}
	
	public Set<String> getExtendedKeywords(String input) {
		
		Set<String> keys = new HashSet<String>();
		
		for (String key : getKeywordsFromString(input))
		{
			keys.add(key);
			keys.addAll(getAssociations(key));
		}
		
		return keys;
	}
}
