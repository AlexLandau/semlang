package semlang.transforms;

import semlang.api.ValidatedContext

/*
 * TODO: We may want actual classes here, to support answering questions about what properties
 * the transform changes and what properties it needs to already have
 */
typealias PostValidationTransform = (ValidatedContext) -> ValidatedContext;
