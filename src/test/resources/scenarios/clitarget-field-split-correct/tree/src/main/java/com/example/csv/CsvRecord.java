package com.example.csv;

import java.util.List;

public record CsvRecord(List<String> fields, int startLine) {
}