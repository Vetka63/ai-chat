package main

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestSaveReportIdempotent(t *testing.T) {
	input := SaveInput{
		OperationID: "123e4567-e89b-12d3-a456-426614174000",
		ReportDraft: ReportDraft{
			Title: "Two summaries", Markdown: "# Report\n",
			SourceSummaryIDs: []string{"one", "two"},
		},
	}
	dir := t.TempDir()
	first, err := saveReport(dir, input)
	if err != nil {
		t.Fatal(err)
	}
	expectedDigest := fmt.Sprintf("%x", sha256.Sum256([]byte(input.ReportDraft.Markdown)))
	if first.ContentSHA256 != expectedDigest {
		t.Fatalf("unexpected content digest: %s", first.ContentSHA256)
	}
	second, err := saveReport(dir, input)
	if err != nil || second.ReportID != first.ReportID {
		t.Fatalf("retry: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(dir, first.FileName))
	if err != nil || string(data) != input.ReportDraft.Markdown {
		t.Fatalf("file: %v", err)
	}
	input.ReportDraft.Markdown = "# Different\n"
	if _, err := saveReport(dir, input); err == nil {
		t.Fatal("conflicting retry accepted")
	}
}

func TestSaveReportRejectsPath(t *testing.T) {
	_, err := saveReport(t.TempDir(), SaveInput{
		OperationID: "../../outside",
		ReportDraft: ReportDraft{Title: "x", Markdown: "x", SourceSummaryIDs: []string{"a"}},
	})
	if err == nil {
		t.Fatal("unsafe operation ID accepted")
	}
}
