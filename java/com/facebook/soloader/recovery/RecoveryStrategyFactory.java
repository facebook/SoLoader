/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.soloader.recovery;

public interface RecoveryStrategyFactory {
  /**
   * @return an instance of RecoveryStrategy that will be used to recover from failures to load a
   *     native library. The recover method of the returned object might be called multiple times
   *     until false is returned or until a successfull retry of the load is performed. All calls
   *     are guaranteed to hapen sequentially, within the context of a single library load. This
   *     contract allows the returned strategy object to try more invasive and expensive recovery
   *     mechanisms as long as cheaper options are not effective.
   */
  RecoveryStrategy get();
}
