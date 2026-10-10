import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Search,
  Code,
  FileText,
  Image as ImageIcon,
  Camera,
  Film,
  Music,
  Folder,
  ExternalLink,
  Copy,
  ChevronDown,
  ChevronRight,
  Clock,
  Sparkles
} from 'lucide-react';
import { SearchHit, FileKind } from '../../shared/types.js';
import { EngineStatus } from '../../engine/Engine.js';

declare global {
  interface Window {
    api: import('../preload.js').DigUpAPI;
  }
}

export const SearchPanel: React.FC = () => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [showWeaker, setShowWeaker] = useState(false);
  const [status, setStatus] = useState<EngineStatus | null>(null);
  const [isSearching, setIsSearching] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const searchDebounceRef = useRef<NodeJS.Timeout | null>(null);

  const isCode = query.trim().toLowerCase().startsWith('code:');

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();

    // Listen to engine status
    window.api.getStatus().then(setStatus);
    const unbind = window.api.onStatus((s) => setStatus(s));
    return () => unbind();
  }, []);

  // Handle Query Search
  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      setSelectedIndex(0);
      return;
    }

    if (searchDebounceRef.current) {
      clearTimeout(searchDebounceRef.current);
    }

    setIsSearching(true);
    searchDebounceRef.current = setTimeout(async () => {
      try {
        const hits = await window.api.search(query);
        setResults(hits);
        setSelectedIndex(0);
      } catch (err) {
        console.error(err);
      } finally {
        setIsSearching(false);
      }
    }, 120);

    return () => {
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current);
    };
  }, [query]);

  // Split results into Strong vs Weaker
  const { strongHits, weakerHits, groupedStrong } = useMemo(() => {
    if (results.length === 0) {
      return { strongHits: [], weakerHits: [], groupedStrong: {} };
    }
    const topScore = results[0].score;
    const strong: SearchHit[] = [];
    const weaker: SearchHit[] = [];

    for (const h of results) {
      if (h.score >= topScore * 0.5) {
        strong.push(h);
      } else {
        weaker.push(h);
      }
    }

    const groups: Record<string, SearchHit[]> = {};
    for (const h of strong) {
      if (!groups[h.kind]) groups[h.kind] = [];
      groups[h.kind].push(h);
    }

    return { strongHits: strong, weakerHits: weaker, groupedStrong: groups };
  }, [results]);

  const visibleList = useMemo(() => {
    return showWeaker ? [...strongHits, ...weakerHits] : strongHits;
  }, [strongHits, weakerHits, showWeaker]);

  const selectedHit = visibleList[selectedIndex] || null;

  // Keyboard Navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex((prev) => (prev + 1 < visibleList.length ? prev + 1 : prev));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex((prev) => (prev > 0 ? prev - 1 : 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (selectedHit) {
          if (e.ctrlKey) {
            window.api.revealInExplorer(selectedHit.path);
          } else {
            window.api.openResult(selectedHit);
            window.api.hideSearchPanel();
          }
        }
      } else if (e.key === 'c' && e.ctrlKey) {
        if (selectedHit) {
          window.api.copyToClipboard(selectedHit.path);
        }
      } else if (e.key === 'Escape') {
        window.api.hideSearchPanel();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [visibleList, selectedIndex, selectedHit]);

  const getKindIcon = (kind: FileKind) => {
    switch (kind) {
      case 'screenshot':
        return <Camera className="w-4 h-4 text-purple-400" />;
      case 'image':
        return <ImageIcon className="w-4 h-4 text-pink-400" />;
      case 'pdf':
        return <FileText className="w-4 h-4 text-red-400" />;
      case 'doc':
        return <FileText className="w-4 h-4 text-blue-400" />;
      case 'video':
        return <Film className="w-4 h-4 text-yellow-400" />;
      case 'audio':
        return <Music className="w-4 h-4 text-emerald-400" />;
      case 'code':
        return <Code className="w-4 h-4 text-cyan-400" />;
      default:
        return <Folder className="w-4 h-4 text-gray-400" />;
    }
  };

  const formatMoment = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const getOpenActionTitle = (hit: SearchHit | null) => {
    if (!hit) return 'Open';
    if (hit.kind === 'code') return `Open at line ${Math.round(hit.focus || hit.loc || 1)}`;
    if (hit.loc != null && (hit.kind === 'video' || hit.kind === 'audio')) {
      return `Open at ${formatMoment(hit.loc)}`;
    }
    if (hit.kind === 'pdf' && hit.loc != null) {
      return `Open page ${hit.loc}`;
    }
    return 'Open';
  };

  return (
    <div className="w-[780px] h-[520px] rounded-2xl glass-panel flex flex-col text-sm select-none border border-white/10 overflow-hidden">
      {/* Header Bar */}
      <div className="h-14 px-4 flex items-center gap-3 border-b border-white/10 bg-white/5">
        <div className="text-blue-400">
          {isCode ? (
            <Code className="w-5 h-5 text-cyan-400" />
          ) : (
            <Sparkles className="w-5 h-5 text-blue-400 animate-pulse" />
          )}
        </div>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={
            status?.code?.files
              ? 'Describe it: "zebra in a video", "code: retry with backoff"...'
              : 'Describe it: "payment error screenshot", "zebra in a video"...'
          }
          className="flex-1 bg-transparent border-none outline-none text-lg text-white placeholder-gray-400 font-normal"
        />
        {status?.activity === 'indexing' && (
          <div className="text-xs px-2.5 py-1 rounded-full bg-white/10 text-gray-300 font-mono">
            Indexing {status.doneFiles}/{status.totalFiles}
          </div>
        )}
      </div>

      {/* Main Content Pane */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Results Column (330px) */}
        <div ref={listRef} className="w-[330px] border-r border-white/10 flex flex-col overflow-y-auto custom-scrollbar p-2 gap-1 bg-black/20">
          {results.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center text-gray-500 gap-2 p-6 text-center">
              {query.trim() ? (
                <>
                  <Search className="w-8 h-8 opacity-40" />
                  <p className="font-medium text-gray-400">No good matches</p>
                  <p className="text-xs">Try what you'd see or hear in it, or words you remember.</p>
                </>
              ) : (
                <>
                  <Sparkles className="w-8 h-8 opacity-40 text-blue-400" />
                  <p className="font-medium text-gray-300">Describe it. Dig it up.</p>
                  <p className="text-xs text-gray-400">
                    Search images, documents, videos, audio, and code across your folders.
                  </p>
                </>
              )}
            </div>
          ) : (
            <>
              {visibleList.map((hit, idx) => {
                const isSelected = idx === selectedIndex;
                const fileName = hit.path.split(/[\\/]/).pop() || '';

                return (
                  <div
                    key={`${hit.path}-${hit.segmentID}-${idx}`}
                    onClick={() => setSelectedIndex(idx)}
                    className={`px-3 py-2 rounded-lg flex items-center gap-2.5 cursor-pointer transition-colors ${
                      isSelected
                        ? 'bg-blue-600/30 text-white border border-blue-500/40'
                        : 'hover:bg-white/5 text-gray-300'
                    }`}
                  >
                    <div className="shrink-0">{getKindIcon(hit.kind)}</div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium truncate text-xs">{fileName}</div>
                      <div className="text-[11px] text-gray-400 truncate">
                        {hit.excerpt || hit.path}
                      </div>
                    </div>
                    {/* Location Badge */}
                    {hit.loc != null && (
                      <span className="shrink-0 text-[10px] px-1.5 py-0.5 rounded bg-white/10 text-gray-300 font-mono">
                        {hit.kind === 'pdf' ? `p. ${hit.loc}` : formatMoment(hit.loc)}
                      </span>
                    )}
                    {hit.kind === 'code' && hit.focus != null && (
                      <span className="shrink-0 text-[10px] px-1.5 py-0.5 rounded bg-cyan-900/40 text-cyan-300 font-mono">
                        L{hit.focus}
                      </span>
                    )}
                  </div>
                );
              })}

              {/* Weaker Matches Toggle */}
              {weakerHits.length > 0 && (
                <button
                  onClick={() => setShowWeaker(!showWeaker)}
                  className="mt-2 text-xs py-1.5 px-3 rounded text-gray-400 hover:text-white hover:bg-white/5 flex items-center gap-1.5 justify-center"
                >
                  {showWeaker ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                  {weakerHits.length} weaker matches
                </button>
              )}
            </>
          )}
        </div>

        {/* Right Preview Pane */}
        <div className="flex-1 flex flex-col p-4 overflow-hidden bg-black/40">
          {selectedHit ? (
            <div className="flex-1 flex flex-col gap-3 overflow-hidden">
              {/* Media Preview Box */}
              <div className="h-[240px] rounded-xl bg-black/60 border border-white/10 flex items-center justify-center overflow-hidden relative">
                {selectedHit.kind === 'image' || selectedHit.kind === 'screenshot' ? (
                  <img
                    src={`file://${selectedHit.path}`}
                    alt="Preview"
                    className="max-h-full max-w-full object-contain"
                  />
                ) : selectedHit.kind === 'video' ? (
                  <video
                    src={`file://${selectedHit.path}`}
                    controls
                    className="max-h-full max-w-full"
                    ref={(el) => {
                      if (el && selectedHit.loc) {
                        el.currentTime = selectedHit.loc;
                      }
                    }}
                  />
                ) : selectedHit.kind === 'audio' ? (
                  <div className="w-full px-6 flex flex-col items-center gap-4">
                    <Music className="w-12 h-12 text-emerald-400 animate-pulse" />
                    <audio
                      src={`file://${selectedHit.path}`}
                      controls
                      className="w-full"
                      ref={(el) => {
                        if (el && selectedHit.loc) {
                          el.currentTime = selectedHit.loc;
                        }
                      }}
                    />
                  </div>
                ) : selectedHit.kind === 'code' ? (
                  <div className="w-full h-full p-3 font-mono text-xs overflow-y-auto custom-scrollbar text-gray-300">
                    <pre className="whitespace-pre-wrap">{selectedHit.excerpt}</pre>
                  </div>
                ) : (
                  <div className="w-full h-full p-4 overflow-y-auto custom-scrollbar text-gray-200">
                    <div className="font-semibold text-xs text-blue-400 mb-2">
                      {selectedHit.kind === 'pdf' ? `Page ${selectedHit.loc || 1}` : 'Excerpt'}
                    </div>
                    <p className="text-xs leading-relaxed">{selectedHit.excerpt}</p>
                  </div>
                )}
              </div>

              {/* Metadata Details */}
              <div className="flex-1 flex flex-col gap-1.5 overflow-hidden">
                <div className="font-medium text-white truncate text-base">
                  {selectedHit.path.split(/[\\/]/).pop()}
                </div>
                <div className="text-xs text-gray-400 truncate">{selectedHit.path}</div>
                <div className="flex items-center gap-3 text-xs text-gray-400 mt-1">
                  <span>{(selectedHit.size / (1024 * 1024)).toFixed(2)} MB</span>
                  <span>•</span>
                  <span>{new Date(selectedHit.modified * 1000).toLocaleDateString()}</span>
                  {selectedHit.z != null && (
                    <>
                      <span>•</span>
                      <span className="text-emerald-400 font-mono">z={selectedHit.z.toFixed(2)}</span>
                    </>
                  )}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center gap-2 pt-2 border-t border-white/10">
                <button
                  onClick={() => {
                    window.api.openResult(selectedHit);
                    window.api.hideSearchPanel();
                  }}
                  className="px-3.5 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium text-xs flex items-center gap-1.5 shadow"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  {getOpenActionTitle(selectedHit)}
                </button>
                <button
                  onClick={() => window.api.revealInExplorer(selectedHit.path)}
                  className="px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/15 text-gray-200 text-xs flex items-center gap-1.5"
                >
                  <Folder className="w-3.5 h-3.5" />
                  Reveal in Explorer
                </button>
                <button
                  onClick={() => window.api.copyToClipboard(selectedHit.path)}
                  className="px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/15 text-gray-200 text-xs flex items-center gap-1.5"
                >
                  <Copy className="w-3.5 h-3.5" />
                  Copy Path
                </button>
              </div>
            </div>
          ) : (
            <div className="flex-1 flex items-center justify-center text-gray-500 text-xs">
              Select a file to preview
            </div>
          )}
        </div>
      </div>

      {/* Footer Bar */}
      <div className="h-8 px-4 flex items-center justify-between border-t border-white/10 bg-white/5 text-[11px] text-gray-400">
        <div>
          {isSearching
            ? 'Matching words; meaning search warming up...'
            : `${results.length} results`}
        </div>
        <div className="flex items-center gap-4 text-gray-500 font-mono">
          <span>↩ Open</span>
          <span>Ctrl+↩ Reveal</span>
          <span>Ctrl+C Copy</span>
          <span>Esc Close</span>
        </div>
      </div>
    </div>
  );
};
