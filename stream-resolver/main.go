package main

import (
	"flag"
	"log"
	"os"

	"github.com/truedem0n/playbridge-stream-resolver/config"
	"github.com/truedem0n/playbridge-stream-resolver/server"
)

func main() {
	cfgPath := flag.String("config", "config.json", "path to config file")
	flag.Parse()

	if _, err := os.Stat(*cfgPath); os.IsNotExist(err) {
		log.Fatalf("config file not found: %s\nCopy config.example.json to config.json and edit it.", *cfgPath)
	}

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		log.Fatalf("loading config: %v", err)
	}

	if len(cfg.Addons) == 0 {
		log.Fatal("no source addons configured — add at least one addon in config.json")
	}

	srv := server.New(cfg, *cfgPath)
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
