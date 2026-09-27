package main

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

// ReportDraft is produced by a different MCP server and validated again here.
type ReportDraft struct {
	Title            string   `json:"title" jsonschema:"Report title"`
	Markdown         string   `json:"markdown" jsonschema:"Complete Markdown report"`
	SourceSummaryIDs []string `json:"source_summary_ids" jsonschema:"IDs of source summaries"`
}

type SaveInput struct {
	ReportDraft ReportDraft `json:"report_draft" jsonschema:"Validated report draft"`
	OperationID string      `json:"operation_id" jsonschema:"Idempotency UUID"`
}

type SaveOutput struct {
	Status           string   `json:"status"`
	ReportID         string   `json:"report_id"`
	FileName         string   `json:"file_name"`
	Title            string   `json:"title"`
	SummaryCount     int      `json:"summary_count"`
	SourceSummaryIDs []string `json:"source_summary_ids"`
	ContentSHA256    string   `json:"content_sha256"`
}

var operationIDPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// saveReport uses a server-owned filename and refuses conflicting retries.
func saveReport(directory string, input SaveInput) (SaveOutput, error) {
	draft := input.ReportDraft
	if !operationIDPattern.MatchString(input.OperationID) {
		return SaveOutput{}, errors.New("invalid operation ID")
	}
	if strings.TrimSpace(draft.Title) == "" || strings.TrimSpace(draft.Markdown) == "" ||
		len(draft.Markdown) > 100000 || len(draft.SourceSummaryIDs) == 0 || len(draft.SourceSummaryIDs) > 20 {
		return SaveOutput{}, errors.New("invalid report draft")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return SaveOutput{}, fmt.Errorf("create report directory: %w", err)
	}
	id := strings.ToLower(input.OperationID)
	name := "report-" + id + ".md"
	path := filepath.Join(directory, name)
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if errors.Is(err, os.ErrExist) {
		existing, readErr := os.ReadFile(path)
		if readErr != nil || string(existing) != draft.Markdown {
			return SaveOutput{}, errors.New("operation ID already saved a different report")
		}
	} else if err != nil {
		return SaveOutput{}, fmt.Errorf("open report: %w", err)
	} else {
		_, writeErr := file.WriteString(draft.Markdown)
		closeErr := file.Close()
		if writeErr != nil || closeErr != nil {
			_ = os.Remove(path)
			return SaveOutput{}, errors.New("cannot save report")
		}
	}
	return SaveOutput{
		Status: "saved", ReportID: id, FileName: name, Title: draft.Title,
		SummaryCount:     len(draft.SourceSummaryIDs),
		SourceSummaryIDs: draft.SourceSummaryIDs,
		ContentSHA256:    fmt.Sprintf("%x", sha256.Sum256([]byte(draft.Markdown))),
	}, nil
}

func main() {
	directory := os.Getenv("REPORT_DIR")
	if directory == "" {
		directory = "/app/data/reports"
	}
	server := mcp.NewServer(&mcp.Implementation{Name: "Go Game Report Store", Version: "1.0.0"}, nil)
	mcp.AddTool(server, &mcp.Tool{
		Name:        "save_report",
		Description: "Save a completed report draft to a Markdown file. Only use after a report draft exists and the user requested saving.",
	}, func(_ context.Context, _ *mcp.CallToolRequest, input SaveInput) (*mcp.CallToolResult, SaveOutput, error) {
		output, err := saveReport(directory, input)
		return nil, output, err
	})
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	mux.Handle("/mcp", mcp.NewStreamableHTTPHandler(
		func(_ *http.Request) *mcp.Server { return server },
		&mcp.StreamableHTTPOptions{JSONResponse: true},
	))
	log.Fatal(http.ListenAndServe(":8080", mux))
}
