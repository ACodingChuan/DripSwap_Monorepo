#!/usr/bin/env python3
"""
Protobuf Schema Analyzer with Semantic Types Support

This script analyzes protobuf files to extract:
- Message structure and field patterns
- Semantic type annotations (sf.substreams.sink.sql.schema.v1.field)
- Routing strategies for fanout configuration

Supports universal protobuf parsing with or without semantic annotations.
"""

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any


class ProtobufField:
    """Represents a protobuf field with semantic annotations"""

    def __init__(self, name: str, field_type: str, field_number: int, is_repeated: bool = False):
        self.name = name
        self.field_type = field_type
        self.field_number = field_number
        self.is_repeated = is_repeated
        self.semantic_type = ""
        self.primary_key = False
        self.foreign_key = ""
        self.format_hint = ""

    def to_semantic_analysis(self, message_name: str) -> Dict[str, Any]:
        """Convert to semantic analysis JSON format"""
        return {
            f"{message_name}.{self.name}": {
                "semantic_type": self.semantic_type,
                "primary_key": self.primary_key,
                "foreign_key": self.foreign_key,
                "format_hint": self.format_hint
            }
        }


class ProtobufOneofField:
    """Represents a protobuf oneof field with its variants"""

    def __init__(self, name: str):
        self.name = name
        self.variants: List[ProtobufField] = []

    def add_variant(self, variant: ProtobufField):
        """Add a variant to this oneof field"""
        self.variants.append(variant)


class ProtobufMessage:
    """Represents a protobuf message with its fields"""

    def __init__(self, name: str):
        self.name = name
        self.fields: List[ProtobufField] = []
        self.oneof_fields: List[ProtobufOneofField] = []
        self.has_oneof = False
        self.table_name = ""

    def add_field(self, field: ProtobufField):
        """Add a field to this message"""
        self.fields.append(field)

    def add_oneof_field(self, oneof_field: ProtobufOneofField):
        """Add a oneof field to this message"""
        self.oneof_fields.append(oneof_field)
        self.has_oneof = True


class ProtobufAnalyzer:
    """Main analyzer class for protobuf files"""

    def __init__(self, proto_file_path: str, primary_message: Optional[str] = None):
        self.proto_file_path = Path(proto_file_path)
        self.primary_message = primary_message
        self.messages: List[ProtobufMessage] = []
        self.has_oneof_fields = False

    def analyze(self) -> Dict[str, Any]:
        """Analyze the protobuf file and return complete analysis"""
        try:
            content = self.proto_file_path.read_text()
        except FileNotFoundError:
            raise FileNotFoundError(f"Protobuf file not found: {self.proto_file_path}")

        self._parse_messages(content)

        routing_strategies = self._generate_routing_strategies()
        return {
            "proto_file": str(self.proto_file_path),
            "analyzed_at": datetime.now().isoformat(),
            "analyzer_version": "2.0.0",
            "routing_strategies": routing_strategies,
            "field_patterns": self._generate_field_patterns(),
            "recommendations": self._generate_recommendations(routing_strategies)
        }

    def _parse_messages(self, content: str):
        """Parse all messages from protobuf content using proper brace counting"""
        # Remove comments and normalize whitespace
        content = re.sub(r'//.*', '', content)
        content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

        # Find all message blocks using brace counting to handle nested structures
        for match in re.finditer(r'message\s+(\w+)\s*\{', content):
            message_name = self._normalize_message_name(match.group(1))
            start_pos = match.end() - 1  # Position of opening brace

            # Count braces to find the matching closing brace
            brace_count = 0
            pos = start_pos

            while pos < len(content):
                char = content[pos]
                if char == '{':
                    brace_count += 1
                elif char == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        # Found matching closing brace - extract message body
                        message_body = content[start_pos + 1:pos]

                        message = ProtobufMessage(message_name)
                        self._parse_message_body(message, message_body)
                        self.messages.append(message)
                        break
                pos += 1

    def _normalize_message_name(self, message_name: str) -> str:
        """Convert ALL CAPS acronyms to Title Case for universal compatibility"""
        import re
        # AllocateLQTY → AllocateLqty, TroveNFTOperation → TroveNftOperation
        # Only convert acronyms that are followed by a capital letter (start of next word) or at end
        return re.sub(r'([A-Z]{2,})(?=[A-Z][a-z]|$)', lambda m: m.group(1).capitalize(), message_name)

    def _parse_message_body(self, message: ProtobufMessage, body: str):
        """Parse fields from message body"""
        # Check for table name annotation
        table_pattern = r'option\s+\(sf\.substreams\.sink\.sql\.schema\.v1\.table\)\s*=\s*\{\s*name:\s*"([^"]+)"'
        table_match = re.search(table_pattern, body)
        if table_match:
            message.table_name = table_match.group(1)

        # Parse oneof fields first
        self._parse_oneof_fields(message, body)

        # Remove oneof blocks from body to avoid parsing their contents as regular fields
        body_without_oneof = self._remove_oneof_blocks(body)

        # Parse individual fields - handle multiline annotations
        field_lines = []
        current_field = ""

        for line in body_without_oneof.split('\n'):
            line = line.strip()
            if not line or line.startswith('//'):
                continue

            current_field += " " + line

            # Complete field when we hit semicolon
            if line.endswith(';'):
                field_lines.append(current_field.strip())
                current_field = ""

        # Process complete field definitions
        for field_def in field_lines:
            field = self._parse_field_definition(field_def)
            if field:
                message.add_field(field)

    def _parse_oneof_fields(self, message: ProtobufMessage, body: str):
        """Parse oneof field blocks from message body"""
        # Pattern to match oneof blocks: oneof FieldName { ... }
        oneof_pattern = r'oneof\s+(\w+)\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}'

        for match in re.finditer(oneof_pattern, body, re.DOTALL):
            oneof_name = match.group(1)
            oneof_body = match.group(2)

            oneof_field = ProtobufOneofField(oneof_name)

            # Parse variants within the oneof block
            # Pattern for oneof variants: Type field_name = number;
            variant_pattern = r'(\w+)\s+(\w+)\s*=\s*(\d+)\s*;'

            for variant_match in re.finditer(variant_pattern, oneof_body):
                variant_type = variant_match.group(1)
                variant_name = variant_match.group(2)
                variant_number = int(variant_match.group(3))

                variant_field = ProtobufField(variant_name, variant_type, variant_number)
                oneof_field.add_variant(variant_field)

            if oneof_field.variants:  # Only add if we found variants
                message.add_oneof_field(oneof_field)
                self.has_oneof_fields = True

    def _parse_field_definition(self, field_def: str) -> Optional[ProtobufField]:
        """Parse a complete field definition with semantic annotations"""
        # Skip option lines and other non-field lines
        if 'option' in field_def or 'enum' in field_def or 'message' in field_def:
            return None

        # Basic field pattern: type name = number [annotations];
        # Handle repeated, optional, etc.
        basic_pattern = r'(repeated\s+|optional\s+)?(\w+)\s+(\w+)\s*=\s*(\d+)(?:\s*\[.*?\])?\s*;'
        match = re.search(basic_pattern, field_def, re.DOTALL)

        if not match:
            return None

        field_modifier = match.group(1)
        field_type = match.group(2)
        field_name = match.group(3)
        field_number = int(match.group(4))

        is_repeated = field_modifier and 'repeated' in field_modifier
        field = ProtobufField(field_name, field_type, field_number, is_repeated)

        # Parse semantic annotations if present
        if '(sf.substreams.sink.sql.schema.v1.field)' in field_def:
            self._parse_semantic_annotations(field, field_def)

        return field

    def _parse_semantic_annotations(self, field: ProtobufField, field_def: str):
        """Parse semantic type annotations from field definition"""
        # Extract the annotation block
        annotation_pattern = r'\(sf\.substreams\.sink\.sql\.schema\.v1\.field\)\s*=\s*\{([^}]+)\}'
        match = re.search(annotation_pattern, field_def, re.DOTALL)

        if not match:
            return

        annotation_content = match.group(1)

        # Parse semantic_type
        semantic_match = re.search(r'semantic_type:\s*"([^"]+)"', annotation_content)
        if semantic_match:
            field.semantic_type = semantic_match.group(1)

        # Parse primary_key
        if re.search(r'primary_key:\s*true', annotation_content):
            field.primary_key = True

        # Parse foreign_key
        fk_match = re.search(r'foreign_key:\s*"([^"]+)"', annotation_content)
        if fk_match:
            field.foreign_key = fk_match.group(1)

        # Parse format_hint
        hint_match = re.search(r'format_hint:\s*"([^"]+)"', annotation_content)
        if hint_match:
            field.format_hint = hint_match.group(1)

    def _extract_oneof_details(self) -> Optional[Dict[str, Any]]:
        """Extract oneof field details for routing strategy"""
        # Look for repeated fields that contain oneof fields
        for message in self.messages:
            if message.oneof_fields:
                # Find the message with oneof fields
                oneof_field = message.oneof_fields[0]  # Take the first oneof field

                # Determine field path based on structure
                # Look for repeated fields that contain this message
                field_path = self._determine_field_path(message)

                # Extract variant names
                variants = [variant.name for variant in oneof_field.variants]

                return {
                    "field_path": field_path,
                    "oneof_field": oneof_field.name,
                    "variants": variants,
                    "message_type": message.name
                }

        return None

    def _determine_field_path(self, target_message: ProtobufMessage) -> str:
        """Determine the field path to reach the oneof field"""
        # Look for repeated fields that reference this message type
        for message in self.messages:
            for field in message.fields:
                if field.field_type == target_message.name and "repeated" in field.field_type:
                    return f"{field.name}[0]"

        # For SPL structure: instructions[0] -> Item oneof field
        if target_message.name == "Instruction":
            return "instructions[0]"

        # Fallback to message name
        return target_message.name.lower()

    def _find_message_by_name(self, name: str) -> Optional['ProtobufMessage']:
        """Find a message by name"""
        for message in self.messages:
            if message.name == name:
                return message
        return None

    def _create_flattened_semantic_entry(
        self,
        parent_message_name: str,
        flattened_field_name: str,
        nested_field: ProtobufField
    ) -> Dict[str, Any]:
        """Create semantic analysis entry for a flattened oneof field"""
        return {
            f"{parent_message_name}.{flattened_field_name}": {
                "semantic_type": nested_field.semantic_type,
                "primary_key": nested_field.primary_key,
                "foreign_key": nested_field.foreign_key,
                "format_hint": nested_field.format_hint
            }
        }

    def _remove_oneof_blocks(self, body: str) -> str:
        """Remove oneof blocks from message body to avoid parsing their contents as regular fields"""
        # Pattern to match oneof blocks: oneof FieldName { ... }
        oneof_pattern = r'oneof\s+\w+\s*\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}'
        return re.sub(oneof_pattern, '', body, flags=re.DOTALL)

    def _get_repeated_fields(self, message: 'ProtobufMessage') -> List[str]:
        """Get list of repeated field names from a message"""
        repeated_fields = []
        for field in message.fields:
            if field.is_repeated:
                repeated_fields.append(field.name)
        return repeated_fields

    def _extract_oneof_details_for_message(self, message: 'ProtobufMessage') -> Optional[Dict[str, Any]]:
        """Extract oneof details for a specific message"""
        for oneof_field in message.oneof_fields:
            if oneof_field.variants:
                return {
                    "field_path": self._determine_field_path(message),
                    "oneof_field": oneof_field.name,
                    "variants": oneof_field.variants,
                    "message_type": message.name
                }
        return None

    def _generate_routing_strategies(self) -> List[Dict[str, Any]]:
        """Generate routing strategy recommendations"""
        strategies = []

        # If primary message is specified, focus only on that message
        if self.primary_message:
            primary_msg = self._find_message_by_name(self.primary_message)
            if primary_msg:
                # Check for repeated fields routing (like Events message)
                repeated_fields = self._get_repeated_fields(primary_msg)
                if len(repeated_fields) > 1:
                    strategies.append({
                        "type": "RepeatedFields",
                        "description": f"Route based on populated repeated fields in {self.primary_message} message",
                        "recommended": True,
                        "complexity": "medium",
                        "details": {
                            "message_type": self.primary_message,
                            "repeated_fields": repeated_fields
                        }
                    })

                # Check for oneof fields in the primary message (only if no repeated fields)
                elif primary_msg.has_oneof:
                    oneof_details = self._extract_oneof_details_for_message(primary_msg)
                    if oneof_details:
                        strategies.append({
                            "type": "OneofField",
                            "description": f"Route based on oneof field variants in {self.primary_message} message",
                            "recommended": True,
                            "complexity": "medium",
                            "details": oneof_details
                        })
            else:
                # Primary message not found, fall back to general strategies
                print(f"Warning: Primary message '{self.primary_message}' not found, using general strategies")

        # General strategies (used when no primary message or as fallback)
        if not self.primary_message or not strategies:
            if self.has_oneof_fields:
                # Find the best oneof field for routing
                oneof_details = self._extract_oneof_details()
                strategy = {
                    "type": "OneofField",
                    "description": "Route based on oneof field variants within repeated message fields",
                    "recommended": True,
                    "complexity": "medium"
                }
                if oneof_details:
                    strategy["details"] = oneof_details
                strategies.append(strategy)

            if len(self.messages) > 1:
                strategies.append({
                    "type": "MessageType",
                    "description": "Route based on different protobuf message types",
                    "recommended": True,
                    "complexity": "low"
                })

        # Always offer single topic as fallback
        strategies.append({
            "type": "SingleTopic",
            "description": "Route all messages to a single topic (simplest approach)",
            "recommended": False,
            "complexity": "minimal"
        })

        return strategies

    def _generate_field_patterns(self) -> Dict[str, Any]:
        """Generate field extraction patterns and semantic analysis"""
        patterns = {
            "extraction_patterns": {
                "generic": {
                    "instruction_id": "fields->>'instruction_id'",
                    "transaction_hash": "fields->>'transaction_hash'",
                    "block_number": "block_number"
                },
                "dynamic": {
                    "description": "Field extraction patterns will be generated based on actual message analysis",
                    "pattern": "fields->'{{message_type}}'->>'{{field_name}}' as {{field_alias}}"
                }
            },
            "semantic_analysis": {},
            # Complete structural information
            "messages": {},
            "oneof_field_type_mappings": {},
            "field_type_info": {},
            "message_dependencies": {}
        }

        # Generate semantic analysis for all messages
        for message in self.messages:
            # Process regular fields (existing logic)
            for field in message.fields:
                # Include all fields in semantic analysis, even those without semantic types
                if not field.semantic_type and field.primary_key:
                    # Primary keys without semantic types get default handling
                    field.semantic_type = ""

                semantic_data = field.to_semantic_analysis(message.name)
                patterns["semantic_analysis"].update(semantic_data)

            # Process oneof fields dynamically
            for oneof_field in message.oneof_fields:
                for variant in oneof_field.variants:
                    # Check if variant.field_type is a message type (dynamic detection)
                    nested_message = self._find_message_by_name(variant.field_type)
                    if nested_message:
                        # Flatten all nested fields from this message
                        for nested_field in nested_message.fields:
                            flattened_name = f"{variant.name}_{nested_field.name}"
                            flattened_semantic_data = self._create_flattened_semantic_entry(
                                message.name, flattened_name, nested_field
                            )
                            patterns["semantic_analysis"].update(flattened_semantic_data)
                        # NOTE: Don't include the simple oneof variant field since we've flattened it
                    else:
                        # Handle primitive oneof variants (if any exist) - only for truly primitive types
                        simple_semantic_data = variant.to_semantic_analysis(message.name)
                        patterns["semantic_analysis"].update(simple_semantic_data)

        # Generate complete structural information
        patterns["messages"] = self._generate_complete_message_info()
        patterns["oneof_field_type_mappings"] = self._generate_oneof_type_mappings()
        patterns["field_type_info"] = self._generate_field_type_info()
        patterns["message_dependencies"] = self._generate_message_dependencies()

        return patterns

    def _generate_recommendations(self, routing_strategies: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Generate analysis recommendations based on actual routing strategies"""
        # Determine preferred strategy from the generated strategies
        # The first recommended strategy is the preferred one
        preferred = "SingleTopic"
        reasoning = "Simple structure, single topic sufficient"

        for strategy in routing_strategies:
            if strategy.get("recommended", False):
                preferred = strategy["type"]

                if preferred == "RepeatedFields":
                    reasoning = f"Multiple repeated fields detected in {self.primary_message or 'primary'} message - route by populated fields"
                elif preferred == "OneofField":
                    reasoning = "Oneof field variants detected - flexible routing by field values"
                elif preferred == "MessageType":
                    reasoning = "Multiple message types detected - route by message type"
                break

        # Generate topic prefix from file name
        topic_prefix = self.proto_file_path.stem.lower().replace('-', '_').replace('.', '_')

        return {
            "preferred_strategy": preferred,
            "reasoning": reasoning,
            "topic_prefix_suggestion": topic_prefix,
            "next_steps": [
                "Generate fanout configuration using recommended strategy",
                "Test with sample data to validate routing"
            ]
        }

    def _generate_complete_message_info(self) -> Dict[str, Any]:
        """Generate complete message structure information"""
        messages_info = {}

        for message in self.messages:
            # Basic message info
            message_info = {
                "name": message.name,
                "table_name": message.table_name,
                "has_oneof": message.has_oneof,
                "fields": [],
                "oneof_fields": [],
                "dependencies": []
            }

            # Process regular fields
            for field in message.fields:
                field_info = {
                    "name": field.name,
                    "type": field.field_type,
                    "type_category": self._classify_field_type(field.field_type),
                    "number": field.field_number,
                    "is_repeated": field.is_repeated,
                    "semantic_type": field.semantic_type,
                    "primary_key": field.primary_key,
                    "foreign_key": field.foreign_key
                }
                message_info["fields"].append(field_info)

                # Track dependencies
                if self._classify_field_type(field.field_type) == "message":
                    if field.field_type not in message_info["dependencies"]:
                        message_info["dependencies"].append(field.field_type)

            # Process oneof fields
            for oneof_field in message.oneof_fields:
                oneof_info = {
                    "name": oneof_field.name,
                    "variants": []
                }

                for variant in oneof_field.variants:
                    variant_info = {
                        "name": variant.name,
                        "type": variant.field_type,
                        "type_category": self._classify_field_type(variant.field_type),
                        "number": variant.field_number
                    }
                    oneof_info["variants"].append(variant_info)

                    # Track dependencies
                    if self._classify_field_type(variant.field_type) == "message":
                        if variant.field_type not in message_info["dependencies"]:
                            message_info["dependencies"].append(variant.field_type)

                message_info["oneof_fields"].append(oneof_info)

            messages_info[message.name] = message_info

        return messages_info

    def _generate_oneof_type_mappings(self) -> Dict[str, str]:
        """Generate deterministic oneof field -> message type mappings"""
        mappings = {}

        for message in self.messages:
            for oneof_field in message.oneof_fields:
                for variant in oneof_field.variants:
                    # Create the field path: MessageName.variant_name -> VariantType
                    field_path = f"{message.name}.{variant.name}"
                    mappings[field_path] = variant.field_type

        return mappings

    def _generate_field_type_info(self) -> Dict[str, Any]:
        """Generate detailed field type information for every field"""
        field_info = {}

        for message in self.messages:
            # Process regular fields
            for field in message.fields:
                field_path = f"{message.name}.{field.name}"
                type_category = self._classify_field_type(field.field_type)

                field_info[field_path] = {
                    "is_primitive": type_category == "primitive",
                    "is_message": type_category == "message",
                    "is_enum": type_category == "enum",
                    "target_type": field.field_type,
                    "target_message": field.field_type if type_category == "message" else None,
                    "is_oneof_variant": False,
                    "oneof_group": None,
                    "is_repeated": field.is_repeated,
                    "semantic_type": field.semantic_type
                }

            # Process oneof variants
            for oneof_field in message.oneof_fields:
                for variant in oneof_field.variants:
                    field_path = f"{message.name}.{variant.name}"
                    type_category = self._classify_field_type(variant.field_type)

                    field_info[field_path] = {
                        "is_primitive": type_category == "primitive",
                        "is_message": type_category == "message",
                        "is_enum": type_category == "enum",
                        "target_type": variant.field_type,
                        "target_message": variant.field_type if type_category == "message" else None,
                        "is_oneof_variant": True,
                        "oneof_group": oneof_field.name,
                        "is_repeated": False,  # Oneof variants are never repeated
                        "semantic_type": getattr(variant, 'semantic_type', '')
                    }

        return field_info

    def _generate_message_dependencies(self) -> Dict[str, List[str]]:
        """Generate message dependency graph"""
        dependencies = {}

        for message in self.messages:
            deps = set()

            # Dependencies from regular fields
            for field in message.fields:
                if self._classify_field_type(field.field_type) == "message":
                    deps.add(field.field_type)

            # Dependencies from oneof variants
            for oneof_field in message.oneof_fields:
                for variant in oneof_field.variants:
                    if self._classify_field_type(variant.field_type) == "message":
                        deps.add(variant.field_type)

            dependencies[message.name] = list(deps)

        return dependencies

    def _classify_field_type(self, field_type: str) -> str:
        """Classify whether a field type is primitive, message, or enum"""
        # Protobuf primitive types
        primitive_types = {
            'double', 'float', 'int32', 'int64', 'uint32', 'uint64',
            'sint32', 'sint64', 'fixed32', 'fixed64', 'sfixed32', 'sfixed64',
            'bool', 'string', 'bytes'
        }

        if field_type in primitive_types:
            return "primitive"

        # Check if it's a message type we've seen
        message_names = {msg.name for msg in self.messages}
        if field_type in message_names:
            return "message"

        # Check for well-known types
        if field_type.startswith('google.protobuf.'):
            return "primitive"  # Treat well-known types as primitives

        # If it starts with uppercase, likely a message type
        if field_type[0].isupper():
            return "message"

        # Default to enum if not recognized
        return "enum"


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description="Analyze protobuf schema for semantic types and routing strategies"
    )
    parser.add_argument(
        "proto_file",
        help="Path to the protobuf (.proto) file to analyze"
    )
    parser.add_argument(
        "-o", "--output",
        help="Output JSON file path (default: stdout)"
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty print JSON output"
    )

    args = parser.parse_args()

    try:
        analyzer = ProtobufAnalyzer(args.proto_file)
        result = analyzer.analyze()

        # Format output
        if args.pretty:
            output = json.dumps(result, indent=2)
        else:
            output = json.dumps(result)

        # Write output
        if args.output:
            Path(args.output).write_text(output)
            print(f"Analysis written to: {args.output}")
        else:
            print(output)

    except Exception as e:
        print(f"Error analyzing protobuf: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
