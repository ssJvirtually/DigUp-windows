import React, { useState, useEffect, useMemo } from 'react';
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
  Settings,
  Sparkles
} from 'lucide-react';
import { SearchHit, FileKind } from '../../shared/types.js';

export const SearchWindow: React.FC = () => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[]>([]);
  const [selectedHit, setSelectedHit] = useState<SearchHit | null>(null);
  const [filter, setFilter] = useState<FileKind | 'all'>('all');

  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      setSelectedHit(null);
      return;
    }
    const timer = setTimeout(async () => {
      const hits = await window.api.search(
        query,
        filter === 'all' ? undefined : [filter]
      );
      setResults(hits);
      if (hits.length > 0) setSelectedHit(hits[0]);
    }, 150);
    return () => clearTimeout(timer);
  }, [query, filter]);

  const kindCounts = useMemo(() => {
    const counts: Record<string, number> = { all: results.length };
    for (const h of results) {
      counts[h.kind] = (counts[h.kind] || 0) + 1;
    }
    return counts;
  }, [results]);

  const filteredHits = useMemo(() => {
    if (filter === 'all') return results;
    return results.filter((h) => h.kind === filter);
  }, [results, filter]);

  return (
    <div className="w-screen h-screen flex flex-col bg-[#121316] text-gray-200 select-none">
      {/* Top Search Toolbar */}
      <div className="h-16 px-6 border-b border-white/10 flex items-center justify-between bg-white/[0.02]">
        <div className="flex items-center gap-3 flex-1 max-w-xl">
          <Search className="w-5 h-5 text-gray-400" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search across all files..."
            className="flex-1 bg-transparent border-none outline-none text-base text-white placeholder-gray-500"
            autoFocus
          />
        </div>
        <button
          onClick={() => window.api.openSettingsWindow()}
          className="p-2 rounded-lg hover:bg-white/10 text-gray-400 hover:text-white"
        >
          <Settings className="w-5 h-5" />
        </button>
      </div>

      {/* Filter Tabs */}
      <div className="h-11 px-6 border-b border-white/10 flex items-center gap-2 bg-black/20 text-xs">
        {(['all', 'screenshot', 'image', 'pdf', 'doc', 'video', 'audio', 'code'] as const).map(
          (k) => (
            <button
              key={k}
              onClick={() => setFilter(k)}
              className={`px-3 py-1 rounded-md capitalize transition-colors flex items-center gap-1.5 ${
                filter === k
                  ? 'bg-blue-600 text-white font-medium'
                  : 'text-gray-400 hover:text-white hover:bg-white/5'
              }`}
            >
              <span>{k === 'all' ? 'All Files' : k}</span>
              {kindCounts[k] ? (
                <span className="opacity-60 text-[10px]">({kindCounts[k]})</span>
              ) : null}
            </button>
          )
        )}
      </div>

      {/* Main Grid & Preview Pane */}
      <div className="flex-1 flex overflow-hidden">
        {/* Results Grid */}
        <div className="flex-1 p-6 overflow-y-auto custom-scrollbar">
          {filteredHits.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-gray-500 gap-2">
              <Sparkles className="w-10 h-10 opacity-30 text-blue-400" />
              <p className="text-sm font-medium">Type a search to explore your files</p>
            </div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              {filteredHits.map((hit) => {
                const isSelected = selectedHit?.path === hit.path;
                const fileName = hit.path.split(/[\\/]/).pop() || '';

                return (
                  <div
                    key={`${hit.path}-${hit.segmentID}`}
                    onClick={() => setSelectedHit(hit)}
                    onDoubleClick={() => window.api.openResult(hit)}
                    className={`rounded-xl p-3 border transition-all cursor-pointer flex flex-col gap-2.5 bg-white/[0.03] ${
                      isSelected
                        ? 'border-blue-500 shadow-lg shadow-blue-500/10 ring-1 ring-blue-500'
                        : 'border-white/10 hover:border-white/20 hover:bg-white/[0.05]'
                    }`}
                  >
                    <div className="h-28 rounded-lg bg-black/40 flex items-center justify-center overflow-hidden">
                      {hit.kind === 'image' || hit.kind === 'screenshot' ? (
                        <img
                          src={`file://${hit.path}`}
                          alt="Thumb"
                          className="max-h-full max-w-full object-contain"
                        />
                      ) : hit.kind === 'video' ? (
                        <Film className="w-8 h-8 text-yellow-400 opacity-60" />
                      ) : hit.kind === 'audio' ? (
                        <Music className="w-8 h-8 text-emerald-400 opacity-60" />
                      ) : hit.kind === 'code' ? (
                        <Code className="w-8 h-8 text-cyan-400 opacity-60" />
                      ) : (
                        <FileText className="w-8 h-8 text-blue-400 opacity-60" />
                      )}
                    </div>
                    <div className="flex flex-col gap-0.5">
                      <div className="font-medium text-xs text-white truncate">{fileName}</div>
                      <div className="text-[11px] text-gray-400 truncate">
                        {hit.excerpt || hit.path}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Right Detail Pane */}
        {selectedHit && (
          <div className="w-80 border-l border-white/10 p-5 flex flex-col gap-4 bg-black/20 overflow-y-auto custom-scrollbar">
            <div className="font-semibold text-white text-base truncate">
              {selectedHit.path.split(/[\\/]/).pop()}
            </div>
            <div className="h-44 rounded-xl bg-black/50 border border-white/10 flex items-center justify-center overflow-hidden">
              {selectedHit.kind === 'image' || selectedHit.kind === 'screenshot' ? (
                <img
                  src={`file://${selectedHit.path}`}
                  alt="Thumb"
                  className="max-h-full max-w-full object-contain"
                />
              ) : selectedHit.kind === 'video' ? (
                <video src={`file://${selectedHit.path}`} controls className="max-h-full max-w-full" />
              ) : (
                <FileText className="w-12 h-12 text-blue-400 opacity-50" />
              )}
            </div>

            <div className="flex flex-col gap-2 text-xs text-gray-300">
              <div>
                <span className="text-gray-500">Kind: </span>
                <span className="capitalize">{selectedHit.kind}</span>
              </div>
              <div>
                <span className="text-gray-500">Size: </span>
                <span>{(selectedHit.size / (1024 * 1024)).toFixed(2)} MB</span>
              </div>
              <div>
                <span className="text-gray-500">Modified: </span>
                <span>{new Date(selectedHit.modified * 1000).toLocaleString()}</span>
              </div>
              <div className="text-gray-400 break-all">{selectedHit.path}</div>
            </div>

            <div className="flex flex-col gap-2 mt-auto pt-4 border-t border-white/10">
              <button
                onClick={() => window.api.openResult(selectedHit)}
                className="w-full py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium text-xs flex items-center justify-center gap-1.5"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                Open File
              </button>
              <button
                onClick={() => window.api.revealInExplorer(selectedHit.path)}
                className="w-full py-2 rounded-lg bg-white/10 hover:bg-white/15 text-gray-200 text-xs flex items-center justify-center gap-1.5"
              >
                <Folder className="w-3.5 h-3.5" />
                Reveal in Explorer
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
