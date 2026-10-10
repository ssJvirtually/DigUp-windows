import React, { useState, useEffect } from 'react';
import { Sparkles, Download, Folder, Check, ArrowRight, ShieldCheck } from 'lucide-react';
import { ModelDownloadProgress } from '../../worker/ModelFiles.js';

export const Onboarding: React.FC = () => {
  const [step, setStep] = useState<1 | 2 | 3 | 4>(1);
  const [downloadProgress, setDownloadProgress] = useState<ModelDownloadProgress | null>(null);
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadDone, setDownloadDone] = useState(false);
  const [selectedFolders, setSelectedFolders] = useState<string[]>([]);

  useEffect(() => {
    const unbind = window.api.onModelDownloadProgress((p) => {
      setDownloadProgress(p);
      if (p.percent >= 100) {
        setDownloadDone(true);
        setIsDownloading(false);
      }
    });
    return () => unbind();
  }, []);

  const startDownload = async () => {
    setIsDownloading(true);
    try {
      await window.api.startModelDownload();
      setDownloadDone(true);
      setIsDownloading(false);
    } catch (err) {
      console.error(err);
      setIsDownloading(false);
      // Even if offline, allow continuing with standard search
      setDownloadDone(true);
    }
  };

  const addFolder = async () => {
    const chosen = await window.api.chooseFolder();
    if (chosen && !selectedFolders.includes(chosen)) {
      setSelectedFolders([...selectedFolders, chosen]);
    }
  };

  const finishOnboarding = async () => {
    await window.api.saveSettings({
      roots: selectedFolders,
      onboarded: true
    });
    window.close();
  };

  return (
    <div className="w-screen h-screen flex flex-col bg-[#141518] text-gray-200 select-none p-8 justify-between">
      {/* Step 1: Welcome */}
      {step === 1 && (
        <div className="flex-1 flex flex-col items-center justify-center text-center max-w-md mx-auto gap-4">
          <div className="w-16 h-16 rounded-2xl brand-gradient-bg flex items-center justify-center shadow-lg shadow-blue-500/20 mb-2">
            <Sparkles className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Welcome to DigUp</h1>
          <p className="text-gray-400 text-sm leading-relaxed">
            Search the files on your PC by what's in them, with Google DeepMind's{' '}
            <span className="text-white font-medium">EmbeddingGemma 2</span>.
          </p>
          <div className="flex items-center gap-2 text-xs text-emerald-400 bg-emerald-950/40 border border-emerald-500/20 px-3 py-1.5 rounded-full mt-2">
            <ShieldCheck className="w-4 h-4" />
            <span>100% Private. Nothing leaves your PC.</span>
          </div>
          <button
            onClick={() => setStep(2)}
            className="mt-6 px-6 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-medium text-sm flex items-center gap-2 shadow-lg shadow-blue-600/30 transition-all"
          >
            <span>Get Started</span>
            <ArrowRight className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Step 2: Model Download */}
      {step === 2 && (
        <div className="flex-1 flex flex-col items-center justify-center text-center max-w-md mx-auto gap-4">
          <div className="w-14 h-14 rounded-2xl bg-white/[0.05] border border-white/10 flex items-center justify-center mb-2">
            <Download className="w-7 h-7 text-blue-400" />
          </div>
          <h2 className="text-xl font-bold text-white">AI Model Setup</h2>
          <p className="text-gray-400 text-xs leading-relaxed">
            DigUp runs on EmbeddingGemma 2 (865 MB, once). It puts text, images, audio, and video into a shared
            768-dimensional space.
          </p>

          <div className="w-full bg-white/[0.03] border border-white/10 rounded-xl p-4 mt-2 flex flex-col gap-2">
            <div className="flex justify-between text-xs font-mono text-gray-300">
              <span>{downloadProgress ? downloadProgress.file : 'EmbeddingGemma 2 Q8_0'}</span>
              <span>{downloadProgress ? `${downloadProgress.percent}%` : '865 MB'}</span>
            </div>
            <div className="w-full h-2 rounded-full bg-black/40 overflow-hidden">
              <div
                className="h-full bg-blue-500 transition-all duration-300"
                style={{ width: `${downloadProgress?.percent || (downloadDone ? 100 : 0)}%` }}
              />
            </div>
          </div>

          {!isDownloading && !downloadDone && (
            <button
              onClick={startDownload}
              className="mt-4 px-6 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-medium text-sm flex items-center gap-2 shadow-lg shadow-blue-600/30"
            >
              <Download className="w-4 h-4" />
              <span>Download Model</span>
            </button>
          )}

          {downloadDone && (
            <button
              onClick={() => setStep(3)}
              className="mt-4 px-6 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-medium text-sm flex items-center gap-2 shadow-lg shadow-emerald-600/30"
            >
              <Check className="w-4 h-4" />
              <span>Continue</span>
            </button>
          )}

          {!downloadDone && !isDownloading && (
            <button
              onClick={() => setStep(3)}
              className="text-xs text-gray-400 hover:text-white mt-1"
            >
              Skip for now
            </button>
          )}
        </div>
      )}

      {/* Step 3: Pick Folders */}
      {step === 3 && (
        <div className="flex-1 flex flex-col items-center justify-center text-center max-w-md mx-auto gap-4">
          <div className="w-14 h-14 rounded-2xl bg-white/[0.05] border border-white/10 flex items-center justify-center mb-2">
            <Folder className="w-7 h-7 text-blue-400" />
          </div>
          <h2 className="text-xl font-bold text-white">Pick Folders to Search</h2>
          <p className="text-gray-400 text-xs leading-relaxed">
            DigUp only searches the folders you pick. Add folders like Documents, Downloads, or Pictures.
          </p>

          <div className="w-full flex flex-col gap-2 max-h-40 overflow-y-auto custom-scrollbar my-2">
            {selectedFolders.map((f) => (
              <div
                key={f}
                className="px-3 py-2 rounded-lg bg-white/[0.04] border border-white/10 text-xs text-left truncate"
              >
                {f}
              </div>
            ))}
          </div>

          <button
            onClick={addFolder}
            className="px-4 py-2 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-medium flex items-center gap-2"
          >
            <Folder className="w-4 h-4 text-blue-400" />
            <span>Add Folder...</span>
          </button>

          <button
            onClick={() => setStep(4)}
            className="mt-4 px-6 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-medium text-sm flex items-center gap-2 shadow-lg shadow-blue-600/30"
          >
            <span>Continue</span>
            <ArrowRight className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Step 4: Ready */}
      {step === 4 && (
        <div className="flex-1 flex flex-col items-center justify-center text-center max-w-md mx-auto gap-4">
          <div className="w-16 h-16 rounded-2xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center mb-2">
            <Check className="w-8 h-8 text-emerald-400" />
          </div>
          <h2 className="text-2xl font-bold text-white">You're All Set!</h2>
          <p className="text-gray-400 text-sm leading-relaxed">
            Press <kbd className="px-2.5 py-1 rounded bg-white/10 text-white font-mono text-xs border border-white/20">Alt+Space</kbd> anytime
            in any app to open DigUp and search your files.
          </p>
          <button
            onClick={finishOnboarding}
            className="mt-6 px-8 py-3 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-semibold text-sm shadow-xl shadow-blue-600/30"
          >
            Start Using DigUp
          </button>
        </div>
      )}

      {/* Footer Step Indicators */}
      <div className="flex items-center justify-center gap-2 h-6">
        {[1, 2, 3, 4].map((s) => (
          <div
            key={s}
            className={`w-2 h-2 rounded-full transition-all ${
              step === s ? 'w-6 bg-blue-500' : 'bg-white/20'
            }`}
          />
        ))}
      </div>
    </div>
  );
};
