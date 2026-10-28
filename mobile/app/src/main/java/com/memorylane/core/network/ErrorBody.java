package com.memorylane.core.network;

/**
 * Error body shape from docs/api-contract.md: {"code":"...","message":"..."}. Field names are
 * the JSON keys read by Gson; keep them unchanged (and kept by R8 if minification is enabled).
 */
final class ErrorBody {
    String code;
    String message;
}
