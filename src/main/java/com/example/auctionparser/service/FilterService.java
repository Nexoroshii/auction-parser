package com.example.auctionparser.service;

import com.example.auctionparser.model.SearchFilter;
import com.example.auctionparser.repository.FilterRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application service for managing search filters. */
@Service
public class FilterService {

    private final FilterRepository repository;

    public FilterService(FilterRepository repository) {
        this.repository = repository;
    }

    public List<SearchFilter> findAll() {
        return repository.findAll();
    }

    public List<SearchFilter> findEnabled() {
        return repository.findEnabled();
    }

    public SearchFilter save(SearchFilter filter) {
        return repository.save(filter);
    }

    public void delete(long id) {
        repository.delete(id);
    }
}
