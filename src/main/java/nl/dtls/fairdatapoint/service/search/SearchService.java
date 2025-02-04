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
package nl.dtls.fairdatapoint.service.search;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import nl.dtls.fairdatapoint.api.dto.search.*;
import nl.dtls.fairdatapoint.database.ontology.OntologySearcher;
import nl.dtls.fairdatapoint.database.rdf.repository.exception.MetadataRepositoryException;
import nl.dtls.fairdatapoint.database.rdf.repository.generic.GenericMetadataRepository;
import nl.dtls.fairdatapoint.entity.exception.ResourceNotFoundException;
import nl.dtls.fairdatapoint.entity.metadata.MetadataState;
import nl.dtls.fairdatapoint.entity.ontology.TermAssociation;
import nl.dtls.fairdatapoint.entity.search.SearchFilterCacheContainer;
import nl.dtls.fairdatapoint.entity.search.SearchFilterType;
import nl.dtls.fairdatapoint.entity.search.SearchFilterValue;
import nl.dtls.fairdatapoint.entity.search.SearchResult;
import nl.dtls.fairdatapoint.entity.settings.SettingsSearchFilter;
import nl.dtls.fairdatapoint.service.metadata.state.MetadataStateService;
import nl.dtls.fairdatapoint.service.settings.SettingsService;
import org.apache.commons.lang.text.StrSubstitutor;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static nl.dtls.fairdatapoint.util.ValueFactoryHelper.i;
import static nl.dtls.fairdatapoint.util.ValueFactoryHelper.l;

@Slf4j
@Service
public class SearchService {

    private static final String QUERY_TEMPLATE_NAME = "queryTemplate.sparql";

    private static final String QUERY_TEMPLATE = loadSparqlQueryTemplate();

    @Autowired
    private GenericMetadataRepository metadataRepository;

    @Autowired
    private MetadataStateService metadataStateService;

    @Autowired
    private SearchMapper searchMapper;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private SearchFilterCache searchFilterCache;

    @Autowired
    private OntologySearcher ontologySearcher;

    @Autowired
    @Qualifier("persistentUrl")
    private String persistentUrl;

    public List<SearchResultDTO> search(
            SearchSavedQueryDTO searchSavedQueryDTO
    ) throws MetadataRepositoryException {
    	
        return search(searchSavedQueryDTO.getVariables());
    }
    
    public List<SearchResultDTO> search(SearchQueryDTO reqDto) throws MetadataRepositoryException {

		log.info("A regular search has been submitted with query {}", reqDto.getQuery());

        final List<SearchResult> results = metadataRepository.findByLiteral(l(reqDto.getQuery()));
        return processSearchResults(results);
    }

    /**
     * Count how often a given word occurs in a searchresult's document.
     * 
     * @param result the search result, corresponding to a metadata document
     * @param searchWord the word to search for
     * @return the number of occurrences of the word
     */
    private int countWordOccurenceIn(SearchResult result, String searchWord) {
    	int count = 0;
    	for (String word : ontologySearcher.getKeywordsFromString(result.getTitle() + result.getDescription())) {
    		
    		if (word.equals(searchWord))
    			count ++;
    	}
    	return count;
	}

    /**
     * Count the number of words in the given search result's document.
     * 
     * @param result the search result, corresponding to a metadata document
     * @return the number of words in the document
     */
	private int countWordsIn(SearchResult result) {
    	return ontologySearcher.getKeywordsFromString(result.getTitle() + result.getDescription()).size();
	}
	
	/**
	 * Sort all search results by their score. highest score goes first.
	 * 
	 * @param results the list of results to sort
	 * @param resultScores the scores to sort by
	 */
	private void sortSearchResultsByScores(List<SearchResult> results, final Map<SearchResult, Double> resultScores) {

    	Collections.sort(results, new Comparator<SearchResult>() {
    		
    		public int compare(SearchResult r1, SearchResult r2) {
    			
    			if (resultScores.get(r1) > resultScores.get(r2))
    				return -1;
    			else if (resultScores.get(r1) < resultScores.get(r2))
    				return 1;
    			else
    				return 0;
    		}
    	});
	}

	private double associationRelevanceThreshold;
	
	/**
	 * Find words associated with the keywords in the query.
	 * Increasing the relevance threshold setting will lead to a faster search, but with less search words, thus less hits.
	 * 
	 * @param query a string holding keywords to search
	 * @return all the words associated with the query
	 */
	private Set<String> findAssociatedWords(String query)
	{
    	List<TermAssociation> associations = ontologySearcher.getAssociations(query);

    	Set<String> words = new HashSet<String>(ontologySearcher.getKeywordsFromString(query));
    	for (TermAssociation association : associations) {
    		
    		words.add(association.getValue());
    	}
    	
    	return words;
	}
	
	/**
	 * Search for the given set of words through the metadata documents and at the same time, score
	 * each result by TF-IDF.
	 * 
	 * @param words the input words, each will be search for independently
	 * @return the resuling metadata documents, with associated TF-IDF scores
	 * @throws MetadataRepositoryException, if there are problems retrieving metadata from the repository.
	 */
	public Map<SearchResult, Double> searchScoreTfidf(Set<String> words) throws MetadataRepositoryException {

    	// Count the total amount of documents in the triple store.
    	// This is relevant for scoring the results.
    	int total = metadataRepository.countTotal();

    	// Search for the words in the triple store and score the results
    	Map<SearchResult, Double> resultScores = new HashMap<SearchResult, Double>();
    	
    	for (String word : words) {

    		// Search for documents, having this word.
    		List<SearchResult> results = metadataRepository.findByLiteral(l(word));
    		if (results.size() == 0)
    			continue;

            log.info("{} results for word \"{}\"", results.size(), word);
    		
    		// Calculate the inverse document frequency for the word.
    		double idf = Math.log(((double)total) / results.size());
    		
    		for (SearchResult result : results) {
			
    			// Calculate the term frequency for this word in this document.
    			int resultWordCount = countWordsIn(result),
    				resultMatchCount = countWordOccurenceIn(result, word);
    			
    			double tf = ((double)resultMatchCount) / resultWordCount;
    			
    			// Add up the scores for the result
    			double score = tf * idf;
    			if (resultScores.containsKey(result))
					score += resultScores.get(result);
    			
                log.info("{} gets score {}", result.getTitle(), score);

				resultScores.put(result, score);
    		}
    	}
    	
    	return resultScores;
	}

	/**
	 * This is an extended search functionality. It uses ontology files that have been indexed and stored beforehand.
	 * if a query's keyword matches with a term in the ontology, then other terms from this ontology are added.
	 * 
	 * @param reqDto the search request
	 * @return the search result response
	 * @throws MetadataRepositoryException, if there are problems retrieving metadata
	 */
    public List<SearchResultDTO> searchAssociations(SearchQueryDTO reqDto) throws MetadataRepositoryException {
    	    	
    	// Expand the number of words to search for, using the web ontologies.
    	Set<String> words = findAssociatedWords(reqDto.getQuery());
    	
    	// Search for the words in the triple store and score the results
    	Map<SearchResult, Double> resultScores = searchScoreTfidf(words);
    	
    	// Sort by the scores we calculated earlier
    	List<SearchResult> results = new ArrayList<SearchResult>(resultScores.keySet());
    	sortSearchResultsByScores(results, resultScores);
    	
        return processSearchResults(results);
    }

	public List<SearchResultDTO> search(
            SearchQueryVariablesDTO reqDto
    ) throws MetadataRepositoryException, MalformedQueryException {
		
    	// Compose query
        final String query = composeQuery(reqDto);
        
		log.info("A SPARQL-based search has been submitted with query {}", query);

        // Verify query
        final SPARQLParser parser = new SPARQLParser();
        parser.parseQuery(query, persistentUrl);
        // Get and process results for query
        final List<SearchResult> results = metadataRepository.findBySparqlQuery(query);
        
        return processSearchResults(results);
    }

    public SearchQueryTemplateDTO getSearchQueryTemplate() {
        return searchMapper.toQueryTemplateDTO(QUERY_TEMPLATE);
    }

    public List<SearchFilterDTO> getSearchFilters() {
        return settingsService
                .getOrDefaults()
                .getSearchFilters()
                .parallelStream()
                .map(this::enrichItems)
                .toList();
    }

    public List<SearchFilterDTO> resetSearchFilters() {
        searchFilterCache.clearCache();
        return getSearchFilters();
    }

    private SearchFilterDTO enrichItems(SettingsSearchFilter filter) {
        final SearchFilterDTO result = searchMapper.toFilterDTO(filter);
        final Set<String> values =
                result
                        .getValues()
                        .stream()
                        .map(SearchFilterItemDTO::getValue)
                        .collect(Collectors.toSet());
        if (filter.isQueryFromRecords()) {
            final List<SearchFilterItemDTO> xvalues = new ArrayList<>();
            xvalues.addAll(result.getValues());
            xvalues.addAll(
                queryFilterItems(filter.getPredicate())
                    .stream()
                    .filter(item -> !values.contains(item.getValue()))
                    .map(searchMapper::toFilterItemDTO)
                    .toList()
            );
            if (filter.getType().equals(SearchFilterType.IRI)) {
                searchFilterCache.updateLabels(filter.getPredicate());
            }
            result.setValues(xvalues);
        }
        return result;
    }

    private List<SearchFilterValue> queryFilterItems(String predicate) {
        // TODO: filter related to DRAFT records
        final SearchFilterCacheContainer cacheContainer =
                searchFilterCache.getFilter(predicate);
        if (cacheContainer != null) {
            return cacheContainer.getValues();
        }
        try {
            final List<SearchFilterValue> result =
                    metadataRepository.findByFilterPredicate(i(predicate));
            searchFilterCache.setFilter(predicate, new SearchFilterCacheContainer(result));
            return result;
        }
        catch (MetadataRepositoryException exception) {
            throw new RuntimeException(exception);
        }
    }

    private List<SearchResultDTO> processSearchResults(List<SearchResult> results) {
        final Map<String, SearchResultDTO> resultDtos = results
                .stream()
                .collect(
                        Collectors.groupingBy(
                                SearchResult::getUri,
                                Collectors.mapping(Function.identity(), toList())
                        )
                )
                .entrySet()
                .parallelStream()
                .filter(entry -> isUsableForFilter(i(entry.getKey())))
                .map(entry -> searchMapper.toResultDTO(entry.getKey(), entry.getValue()))
                .collect(Collectors.toMap(SearchResultDTO::getUri, Function.identity()));
        return results
                .stream()
                .map(SearchResult::getUri)
                .distinct()
                .map(resultDtos::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isUsableForFilter(IRI iri) {
        try {
            return !metadataStateService
                    .get(iri)
                    .getState()
                    .equals(MetadataState.DRAFT);
        }
        catch (ResourceNotFoundException exception) {
            return true;
        }
    }

    private String composeQuery(SearchQueryVariablesDTO reqDto) {
        final StrSubstitutor substitutor =
                new StrSubstitutor(searchMapper.toSubstitutions(reqDto), "{{", "}}");
        return substitutor.replace(QUERY_TEMPLATE);
    }

    private static String loadSparqlQueryTemplate() {
        try {
            final URL fileURL = SearchService.class.getResource(QUERY_TEMPLATE_NAME);
            return Resources.toString(fileURL, Charsets.UTF_8);
        }
        catch (IOException exception) {
            throw new RuntimeException(
                    format("Cannot load SPARQL template for search: %s",
                            exception.getMessage())
            );
        }
    }
}
