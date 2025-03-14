/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! A thin wrapper over `base64-simd`

use std::error::Error;

use base64_simd::Base64;

/// Failure to decode a base64 value.
#[derive(Debug)]
pub struct DecodeError(base64_simd::Error);

impl Error for DecodeError {}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(f)
    }
}

/// Decode `input` from base64 using the standard base64 alphabet
///
/// If input is not a valid base64 encoded string, this function will return `DecodeError`.
pub fn decode(input: impl AsRef<str>) -> Result<Vec<u8>, DecodeError> {
    Base64::STANDARD
        .decode_to_boxed_bytes(input.as_ref().as_bytes())
        .map(|bytes| bytes.into_vec())
        .map_err(DecodeError)
}

/// Encode `input` into base64 using the standard base64 alphabet
pub fn encode(input: impl AsRef<[u8]>) -> String {
    Base64::STANDARD
        .encode_to_boxed_str(input.as_ref())
        .into_string()
}

/// Given the length of some data in bytes, return how many bytes it would take to base64 encode
///
/// that data.
pub fn encoded_length(length: usize) -> usize {
    Base64::STANDARD.encoded_length(length)
}
