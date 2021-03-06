import React, { PropTypes as T } from 'react';
import { Link } from 'react-router';

import { momentFormat, momentDurationFormat } from '../utils';

import CopyToClipboard from 'react-copy-to-clipboard';

export default class TableRow extends React.Component {

  static propTypes = {
    toggleRowDetails: T.func.isRequired,
    row: T.shape({
      id          : T.string.isRequired,
      name        : T.string.isRequired,
      user        : T.string.isRequired,
      status      : T.string.isRequired,
      clusterName : T.string,
      started     : T.string,
      finished    : T.string,
      runtime     : T.string,
    }),
  };

  constructor(props) {
    super(props);
    this.state = {
      copied : false,
    };
  }

  get jobsUrl() {
    const {protocol, hostname, pathname} = window.location;
    return `${protocol}//${hostname}${pathname}`;
  }

  render() {
    return (
      <tr className="job-table-row" >
        <td className="link" onClick={() => this.props.toggleRowDetails(this.props.row.id)}>
          {this.props.row.id}
        </td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)}>{this.props.row.name}</td>
        <td>
          <Link
            target="_blank"
            to={`/output/${this.props.row.id}/output`}
          >
            <i className="fa fa-lg fa-folder" aria-hidden="true"></i>
          </Link>
        </td>
        <td onMouseMove={() => { this.setState({ copied: false }); }}>
          <CopyToClipboard
            onCopy={() => this.setState({ copied: true })}
            text={`${this.jobsUrl}?id=${this.props.row.id}&rowId=${this.props.row.id}`}
          >
            <button className="btn btn-default btn-xs">
              <i className="fa fa-share-alt" aria-hidden="true"></i>
            </button>
          </CopyToClipboard>
          {this.state.copied ? <p className="text-muted copied-text">Copied</p> : null}
        </td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)}>{this.props.row.user}</td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)}>{this.props.row.status}</td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)}>{this.props.row.clusterName}</td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)} className="col-xs-2">
          {this.props.row.started ? momentFormat(this.props.row.started) : 'NA'}
        </td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)} className="col-xs-2">
          {this.props.row.finished ? momentFormat(this.props.row.finished) : 'NA'}
        </td>
        <td onClick={() => this.props.toggleRowDetails(this.props.row.id)} className="col-xs-1">
          {this.props.row.started ? momentDurationFormat(this.props.row.runtime) : 'NA'}
        </td>
      </tr>
    );
  }
}
